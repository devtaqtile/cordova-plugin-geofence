package com.cowbell.cordova.geofence;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.Manifest;
import android.widget.Toast;

import com.firebase.geofire.GeoFire;
import com.firebase.geofire.GeoLocation;
import com.firebase.geofire.GeoQuery;
import com.firebase.geofire.GeoQueryEventListener;
import com.firebase.geofire.LocationCallback;
import com.firebase.geofire.util.GeoUtils;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PermissionHelper;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class GeofencePlugin extends CordovaPlugin {
  public static final String TAG = "GeofencePlugin";

  public static final String ERROR_UNKNOWN = "UNKNOWN";
  public static final String ERROR_PERMISSION_DENIED = "PERMISSION_DENIED";
  public static final String ERROR_GEOFENCE_NOT_AVAILABLE = "GEOFENCE_NOT_AVAILABLE";
  public static final String ERROR_GEOFENCE_LIMIT_EXCEEDED = "GEOFENCE_LIMIT_EXCEEDED";
  private static final float RADIUS = 16f;
  private static final int COUNT_OF_POINTS = 3;

  static final Comparator<GeoNotification> GEO_NOTIFICATION_COMPARATOR =
    new Comparator<GeoNotification>() {
      public int compare(GeoNotification gn1, GeoNotification gn2) {
        return Double.compare(gn1.distance, gn2.distance);
      }
    };

  private GeoNotificationManager geoNotificationManager;
  private TransitionReceiver transitionReceiver;
  private Context context;
  public static CordovaWebView webView = null;

  private class Action {
    public String action;
    public JSONArray args;
    public CallbackContext callbackContext;

    public Action(String action, JSONArray args, CallbackContext callbackContext) {
      this.action = action;
      this.args = args;
      this.callbackContext = callbackContext;
    }
  }

  //FIXME: what about many executedActions at once
  private Action executedAction;

  /**
   * @param cordova The context of the main Activity.
   * @param webView The associated CordovaWebView.
   */
  @Override
  public void initialize(CordovaInterface cordova, CordovaWebView webView) {
    super.initialize(cordova, webView);
    GeofencePlugin.webView = webView;
    context = this.cordova.getActivity().getApplicationContext();
    Logger.setLogger(new Logger(TAG, context, false));
    geoNotificationManager = new GeoNotificationManager(context);

    transitionReceiver = new TransitionReceiver();
    transitionReceiver.onReceive(context, this.cordova.getActivity().getIntent());
    initFireBase();
  }

  @Override
  public boolean execute(final String action, final JSONArray args,
                         final CallbackContext callbackContext) throws JSONException {
    Log.d(TAG, "GeofencePlugin execute action: " + action + " args: " + args.toString());
    executedAction = new Action(action, args, callbackContext);

    cordova.getThreadPool().execute(new Runnable() {
      public void run() {
        if (action.equals("addOrUpdate")) {
          List<GeoNotification> geoNotifications = new ArrayList<GeoNotification>();
          for (int i = 0; i < args.length(); i++) {
            GeoNotification not = parseFromJSONObject(args.optJSONObject(i));
            if (not != null) {
              geoNotifications.add(not);
            }
          }
          geoNotificationManager.addGeoNotifications(geoNotifications, callbackContext);
        } else if (action.equals("remove")) {
          List<String> ids = new ArrayList<String>();
          for (int i = 0; i < args.length(); i++) {
            ids.add(args.optString(i));
          }
          geoNotificationManager.removeGeoNotifications(ids, callbackContext);
        } else if (action.equals("removeAll")) {
          geoNotificationManager.removeAllGeoNotifications(callbackContext);
        } else if (action.equals("getWatched")) {
          List<GeoNotification> geoNotifications = geoNotificationManager.getWatched();
          callbackContext.success(Gson.get().toJson(geoNotifications));
        } else if (action.equals("initialize")) {
          initialize(callbackContext);
        } else if (action.equals("deviceReady")) {
          deviceReady();
        }
      }
    });

    return true;
  }

  public void initFireBase() {
    FirebaseApp.initializeApp(this.cordova.getActivity());
    DatabaseReference ref = FirebaseDatabase.getInstance().getReference("geoFences");
    final DatabaseReference detailsRef = FirebaseDatabase.getInstance().getReference("geoFencesDetails");
    final ArrayList<GeoNotification> geoNots = new ArrayList<GeoNotification>();
    final GeoFire geoFire = new GeoFire(ref);

    FusedLocationProviderClient mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this.cordova.getActivity());
    if (ActivityCompat.checkSelfPermission(this.cordova.getActivity(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
      ActivityCompat.checkSelfPermission(this.cordova.getActivity(), Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
      // TODO: Consider calling

      ActivityCompat.requestPermissions(this.cordova.getActivity(), new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 2);
      ActivityCompat.requestPermissions(this.cordova.getActivity(), new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 1);

      return;
    }

    mFusedLocationClient.getLastLocation().addOnCompleteListener(this.cordova.getActivity(), new OnCompleteListener<Location>() {
      @Override
      public void onComplete(@NonNull Task<Location> task) {
        GeoQuery query = geoFire.queryAtLocation(new GeoLocation(task.getResult().getLatitude(), task.getResult().getLongitude()), RADIUS);

        query.addGeoQueryEventListener(new GeoQueryEventListener() {
          @Override
          public void onKeyEntered(final String key, final GeoLocation location) {
            detailsRef.child(key).addValueEventListener(new ValueEventListener() {
              @Override
              public void onDataChange(DataSnapshot dataSnapshot) {
                GeoNotification geoNotification = dataSnapshot.getValue(GeoNotification.class);
                assert geoNotification != null;

                geoNotification.id = key;
                geoNotification.url = "https://dollar-general-e532b.firebaseio.com/geoFencesDetails/" + key;
                //todo add groupId
                geoNotification.groupId = null;
                geoNotification.distance = GeoUtils.distance(location, new GeoLocation(geoNotification.latitude, geoNotification.longitude)) - geoNotification.radius;

                geoNots.add(geoNotification);

                if (geoNots.size() > COUNT_OF_POINTS) {
//                  for (GeoNotification gn : geoNots) {
//                    final GeoNotification nearest = Collections.min(geoNots, new Comparator<GeoNotification>() {
//
//                      public int compare(final GeoNotification gn1, final GeoNotification gn2) {
//                        return (int) Double.compare(gn1.distance, gn2.distance);
//                      }
//                    });
//                  }

                  Collections.sort(geoNots, GEO_NOTIFICATION_COMPARATOR);
                  geoNotificationManager.addGeoNotifications(geoNots.subList(0, COUNT_OF_POINTS), null);
                } else {
                  geoNotificationManager.addGeoNotifications(geoNots, null);
                }
              }

              @Override
              public void onCancelled(DatabaseError databaseError) {
                // Getting Post failed, log a message
                Log.w(TAG, "loadPost:onCancelled", databaseError.toException());
              }
            });
          }

          @Override
          public void onKeyExited(String key) {
            System.out.println(String.format("Key %s is no longer in the search area", key));
            ArrayList<String> ids = new ArrayList<String>();
            ids.add(key);
            geoNotificationManager.removeGeoNotifications(ids, null);
          }

          @Override
          public void onKeyMoved(String key, GeoLocation location) {
            System.out.println(String.format("Key %s moved within the search area to [%f,%f]", key, location.latitude, location.longitude));
          }

          @Override
          public void onGeoQueryReady() {
            System.out.println("All initial data has been loaded and events have been fired!");
          }

          @Override
          public void onGeoQueryError(DatabaseError error) {
            System.err.println("There was an error with this query: " + error);
          }
        });
      }
    });
  }

  public boolean execute(Action action) throws JSONException {
    return execute(action.action, action.args, action.callbackContext);
  }

  private GeoNotification parseFromJSONObject(JSONObject object) {
    GeoNotification geo = GeoNotification.fromJson(object.toString());
    return geo;
  }

  public static void onTransitionReceived(List<GeoNotification> notifications) {
    Log.d(TAG, "Transition Event Received!");
    String js = "setTimeout('geofence.onTransitionReceived("
      + Gson.get().toJson(notifications) + ")',0)";
    if (webView == null) {
      Log.d(TAG, "Webview is null");
    } else {
      webView.sendJavascript(js);
    }
  }

  private void deviceReady() {
    Intent intent = cordova.getActivity().getIntent();
    String data = intent.getStringExtra("geofence.notification.data");
    String js = "setTimeout('geofence.onNotificationClicked(" + data + ")',0)";

    if (data == null) {
      Log.d(TAG, "No notifications clicked.");
    } else {
      webView.sendJavascript(js);
    }
  }

  private void initialize(CallbackContext callbackContext) {
    String[] permissions = {
      Manifest.permission.ACCESS_COARSE_LOCATION,
      Manifest.permission.ACCESS_FINE_LOCATION
    };

    if (!hasPermissions(permissions)) {
      PermissionHelper.requestPermissions(this, 0, permissions);
    } else {
      callbackContext.success();
    }
  }

  private boolean hasPermissions(String[] permissions) {
    for (String permission : permissions) {
      if (!PermissionHelper.hasPermission(this, permission)) return false;
    }

    return true;
  }

  public void onRequestPermissionResult(int requestCode, String[] permissions,
                                        int[] grantResults) throws JSONException {
    PluginResult result;

    if (executedAction != null) {
      for (int r : grantResults) {
        if (r == PackageManager.PERMISSION_DENIED) {
          Log.d(TAG, "Permission Denied!");
          result = new PluginResult(PluginResult.Status.ILLEGAL_ACCESS_EXCEPTION);
          executedAction.callbackContext.sendPluginResult(result);
          executedAction = null;
          return;
        }
      }
      Log.d(TAG, "Permission Granted!");
      execute(executedAction);
      executedAction = null;
    }
  }
}
