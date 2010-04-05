package com.growlforandroid.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.ServerSocketChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import com.growlforandroid.client.R;
import com.growlforandroid.common.Database;
import com.growlforandroid.common.GrowlApplication;
import com.growlforandroid.common.IGrowlRegistry;
import com.growlforandroid.common.NotificationType;
import com.growlforandroid.common.Utility;
import com.growlforandroid.gntp.HashAlgorithm;

import android.app.*;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.os.*;
import android.util.Log;
import android.widget.Toast;

public class GrowlListenerService
	extends Service
	implements IGrowlRegistry {
	
	private static final int GNTP_PORT = 23053;
	
	private final Map<String, GrowlApplication> _applications = new HashMap<String, GrowlApplication>();
	
    private NotificationManager _notifyMgr;
    private ServerSocketChannel _serverChannel;
    private SocketAcceptor _socketAcceptor;
    
    private Database _database;
    
    /**
     * Class for clients to access.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with
     * IPC.
     */
    public class LocalBinder extends Binder {
    	GrowlListenerService getService() {
            return GrowlListenerService.this;
        }
    }
    
    @Override
    public void onCreate() {
    	_notifyMgr = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
    	
        // Display a notification about us starting.  We put an icon in the status bar.
        showNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i("onStartCommand", "Received start id " + startId + ": " + intent);
        if (_serverChannel != null) {
        	Log.i("onStartCommand", "Already started");
        	return START_STICKY;
        }
        
        try {
            // Open the database
            _database = new Database(this.getApplicationContext());
            loadApplicationsFromDatabase();
        	
        	// Start listening on GNTP_PORT, on all interfaces
        	_serverChannel = ServerSocketChannel.open();
        	_serverChannel.socket().bind(new InetSocketAddress(GNTP_PORT));
        	
        	// Start accepting connections on another thread
			_socketAcceptor = new SocketAcceptor(this, _serverChannel);
			_socketAcceptor.start();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        
        
        // We want this service to continue running until it is explicitly
        // stopped, so return sticky.
        return START_STICKY;
    }

	public boolean isRunning() {
    	return _serverChannel != null;
    }
    
    @Override
    public void onDestroy() {
        // Cancel the persistent notification.
    	_notifyMgr.cancel(R.string.service_started);

    	// Stop listening for TCP connections
		try {
	    	if (_socketAcceptor != null) {
	    		_socketAcceptor.interrupt();
	    		_socketAcceptor = null;
	    	}
    		
	    	if (_serverChannel != null) {
	    		_serverChannel.socket().close();
	    		_serverChannel.close();
    			_serverChannel = null;
	    	}
		} catch (Exception x) {
			Log.e("onDestroy", x.toString());
		}
	
		_database.close();
		_database = null;
		_applications.clear();
		
        // Tell the user we stopped.
        Toast.makeText(this, R.string.service_stopped, Toast.LENGTH_SHORT).show();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    // This is the object that receives interactions from clients.  See
    // RemoteService for a more complete example.
    private final IBinder mBinder = new LocalBinder();

    /**
     * Show a notification while this service is running.
     */
    private void showNotification() {
        // In this sample, we'll use the same text for the ticker and the expanded notification
        CharSequence text = getText(R.string.service_started);

        // Set the icon, scrolling text and timestamp
        Notification notification = new Notification(R.drawable.statusbar_enabled, text, System.currentTimeMillis());

        // The PendingIntent to launch our activity if the user selects this notification
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), 0);

        // Set the info for the views that show in the notification panel.
        notification.flags = Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
        notification.setLatestEventInfo(this, getText(R.string.service_label),
                       text, contentIntent);

        // Send the notification.
        // We use a layout id because it is a unique number.  We use it later to cancel.
        _notifyMgr.notify(R.string.service_started, notification);
    }

	public Drawable getIcon(URL icon) {
		// TODO Auto-generated method stub
		return null;
	}


    private void loadApplicationsFromDatabase() {
    	int count = 0;
    	Cursor apps = _database.getAllApplications();
    	if (apps == null)
    		return;
    	
    	if (apps.moveToFirst()) {	    	
	    	do {
	    		count ++;
	    		try {
	    			loadApplication(apps);
	    		} catch (Exception x) {
	    			Log.e("loadApplicationsFromDatabase", "Failed to load an application from the database: " + x);
	    		}
	    	} while (apps.moveToNext());
	    	apps.close();
    	}
    	Log.e("loadApplicationsFromDatabase", "Loaded " + count + " applications from the database");
	}

	private GrowlApplication loadApplication(Cursor cursor) throws MalformedURLException {
		int id = cursor.getInt(0);
		String name = cursor.getString(1);
		boolean enabled = cursor.getInt(2) != 0;
		String icon = cursor.getString(3);
		URL iconUrl = icon == null ? null : new URL(icon);
		
		GrowlApplication app = new GrowlApplication(this, id, name, enabled, iconUrl);
		_applications.put(name, app);
		
		Log.i("loadApplication", "Registered existing application \"" + name + "\" with ID = " + id);
		
		return app;
	}
	
	public GrowlApplication registerApplication(String name, URL iconUrl) {
		// Create a new Application and store application in a dictionary
		GrowlApplication oldApp = _applications.get(name);
		if (oldApp != null) {
			Log.i("registerApplication", "Re-registering application \"" + name + "\" with ID = " + oldApp.ID);
			oldApp.IconUrl = iconUrl;
			return oldApp;
		} else {
			Boolean enabled = true;
			int id = _database.insertApplication(name, enabled, iconUrl);
			GrowlApplication newApp = new GrowlApplication(this, id, name, enabled, iconUrl);
			Log.i("registerApplication", "Registered new application \"" + name + "\" with ID = " + newApp.ID);
			_applications.put(name, newApp);
			return newApp;
		}
	}

	public GrowlApplication getApplication(String name) {
		// Get an application from the dictionary
		return _applications.get(name);
	}
	
	public void displayNotification(NotificationType type, String ID, String title, String text, URL iconUrl) {
		GrowlApplication app = type.Application;
		Log.i("displayNotification", "Displaying notification from \"" + app.Name + "\" " +
				"of type \"" + type.TypeName + "\" with title \"" + title + "\" and text \"" + text + "\"");
		Notification notification = new Notification(R.drawable.statusbar_enabled, text, System.currentTimeMillis());

		// The PendingIntent to launch our activity if the user selects this notification
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), 0);

        // Set the info for the views that show in the notification panel.
        String statusBarTitle = app.Name + ": " + title;
        notification.flags = Notification.FLAG_AUTO_CANCEL | Notification.FLAG_SHOW_LIGHTS;
        notification.setLatestEventInfo(this, statusBarTitle, text, contentIntent);

        // Send the notification to the status bar
        _notifyMgr.notify(app.ID, notification);
	}

	public NotificationType getNotificationType(GrowlApplication application, String typeName) {
		Cursor cursor = _database.getNotificationType(application.ID, typeName);
		if ((cursor != null) && cursor.moveToFirst()) {
			int id = cursor.getInt(0);
			String displayName = cursor.getString(2);
			boolean enabled = cursor.getInt(3) != 0;
			String icon = cursor.getString(4);
			URL iconUrl = null;
			try {
				iconUrl = icon == null ? null : new URL(icon);
			} catch (MalformedURLException e) {
				e.printStackTrace();
			}
			
			return new NotificationType(id, application, typeName, displayName, enabled, iconUrl);
		} else {
			return null;
		}
	}

	public NotificationType registerNotificationType(GrowlApplication application, String typeName, String displayName,	boolean enabled, URL iconUrl) {
		if (displayName == null)
			displayName = typeName;
		int id = _database.insertNotificationType(application.ID, typeName, displayName, enabled, iconUrl);
		return new NotificationType(id, application, typeName, displayName, enabled, iconUrl);
	}

	public boolean isValidHash(HashAlgorithm algorithm, String hash, String salt) {
		byte[] hashBytes = Utility.hexStringToByteArray(hash);
		byte[] saltBytes = Utility.hexStringToByteArray(salt);
		return isValidHash(algorithm, hashBytes, saltBytes);
	}
	
	public boolean isValidHash(HashAlgorithm algorithm, byte[] hash, byte[] salt) {
		// TODO: Populate this with a user-defined list of passwords
		String[] passwords = new String[] { "password" };
		
		for(String password : passwords) {
			byte[] validHash = algorithm.calculateHash(password, salt);
			boolean isValid = Utility.compareArrays(validHash, hash);
			if (isValid)
				return true;
		}
		return false;
	}
}








