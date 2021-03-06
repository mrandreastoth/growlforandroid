package com.growlforandroid.gntp;

import java.io.*;
import java.net.*;
import java.nio.channels.SocketChannel;
import java.util.*;

import com.growlforandroid.common.*;
import com.growlforandroid.common.EncryptedChannelReader.DecryptionException;

import android.content.Context;
import android.util.Log;

/**
 * Listens for incoming GNTP requests on the specified SocketChannel, notifying
 * the specified IGrowlRegistry about each message.
 */
public class GntpListenerThread extends Thread {
	private final SocketAcceptor _acceptor;
	private final long _connectionID;
	private final Socket _socket;
	private final IGrowlService _service;
	private final SocketChannel _channel;
	private final EncryptedChannelReader _socketReader;
	private final ChannelWriter _socketWriter;

	private RequestState _currentState = RequestState.Connected;

	private long _requestStartedMS;
	private RequestType _requestType;
	private EncryptionType _encryptionType;
	private byte[] _initVector;
	private byte[] _key;

	private Map<String, String> _requestHeaders = new HashMap<String, String>();
	private Map<String, GrowlResource> _resources = new HashMap<String, GrowlResource>();
	private Map<String, String> _resourceHeaders = new HashMap<String, String>();
	private int _notificationsCount = 0;
	private int _notificationIndex = 0;
	private int _resourceIndex = 0;
	private Map<Integer, Map<String, String>> _notificationsHeaders = new HashMap<Integer, Map<String, String>>();

	public GntpListenerThread(SocketAcceptor socketAcceptor, IGrowlService service, long connectionID,
			SocketChannel channel) {
		super("GntpListenerThread");

		_requestStartedMS = System.currentTimeMillis();

		_acceptor = socketAcceptor;
		_connectionID = connectionID;
		_service = service;

		_channel = channel;
		_socketReader = new EncryptedChannelReader(_channel);
		_socketWriter = new ChannelWriter(_channel, Constants.CHARSET);

		_socket = channel.socket();
	}

	public Context getContext() {
		return _acceptor.getContext();
	}

	public void run() {
		try {
			int remotePort = _socket.getPort();
			Log.i("GLT:" + _connectionID, "Connected to client on port " + remotePort);

			// Read lines from the socket until we've sent a response back
			String inputLine;
			while ((_currentState != RequestState.ResponseSent) && ((inputLine = _socketReader.readLine()) != null)) {

				// Parse the input
				// Log.i("GNTPListenerThread.run[" + _connectionID + "]",
				// "Read line \"" + inputLine + "\"");
				try {
					// Parse the input
					switch (_currentState) {
					case Connected:
						// Parse first row of request: GNTP/1.0 REGISTER ...
						_currentState = parseRequestLine(inputLine);
						break;

					case ReadingRequestHeaders:
						// Parse request header rows: Application-Name: Growl
						// for Android
						_currentState = parseRequestHeader(inputLine);
						break;

					case ReadingResourceHeaders:
						// Parse the x-growl-resource header rows
						_currentState = parseResourceHeader(inputLine);
						if (_currentState == RequestState.ReadingResourceData) {
							_currentState = readResourceData();
						}

						break;

					case ReadingNotificationHeaders:
						// Parse notification type header rows:
						// Notification-Name: New Mail
						_currentState = parseNotificationHeader(inputLine);
						break;
					}

					// Are we ready to reply?
					if (_currentState == RequestState.EndOfRequest) {
						switch (_requestType) {
						case Register:
							doRegister();
							break;

						case Notify:
							doNotify();
							break;

						case Subscribe:
							doSubscribe();
							break;

						case Ignore:
							// Notification hash is invalid, silently ignore
							// this notification
							Log.w("GLT:" + _connectionID, "Ignoring notification with invalid hash");
							break;

						default:
							throw new GntpException(GntpError.InvalidRequest, "Unexpected message type");
						}

						// Send the response
						Response response = new Response(ResponseType.OK);
						switch (_requestType) {
						case Notify:
							response.addHeader(Constants.HEADER_NOTIFICATION_ID, "");
							break;

						case Subscribe:
							response.addHeader(Constants.HEADER_SUBSCRIPTION_TTL, Constants.SUBSCRIPTION_TTL);
							break;
						}

						response.write(_socketWriter);
						_currentState = RequestState.ResponseSent;
					}

				} catch (Exception x) {
					// Parsing error or something unexpected
					Log.e("GLT:" + _connectionID, "Unexpected error while reading from socket", x);

					// Send the error response
					GntpError error = GntpError.getErrorFrom(x);
					Response response = new Response(ResponseType.Error, error);
					response.addCommonHeaders(getContext());
					response.write(_socketWriter);

					_currentState = RequestState.ResponseSent;
				}
			}

			Log.i("GLT:" + _connectionID, "No more input data, closing client connection from port " + remotePort);
			_channel.close();
			_socket.close();

		} catch (Exception x) {
			Log.e("GLT:" + _connectionID, "Unexpected exception while reading from socket", x);
		}

		// Notify the SocketAcceptor that we're done
		Log.i("GLT:" + _connectionID, "Connection closed");
		_service.connectionClosed(this);
	}

	private RequestState parseRequestHeader(String inputLine) throws GntpException {
		if (inputLine.equals("")) {
			if (_requestType == RequestType.Register) {
				// Prepare to read the headers for each notification type
				_notificationsCount = Integer.valueOf(_requestHeaders.get(Constants.HEADER_NOTIFICATIONS_COUNT));
				return RequestState.ReadingNotificationHeaders;
			} else if (_resources.size() > 0) {
				// This request has one or more resources remaining
				return RequestState.ReadingResourceHeaders;
			} else {
				// We're done
				return RequestState.EndOfRequest;
			}
		}

		// Parse the header into the _requestHeaders map
		parseHeader(inputLine, _requestHeaders);
		return RequestState.ReadingRequestHeaders;
	}

	private RequestState parseNotificationHeader(String inputLine) throws GntpException, IOException,
			DecryptionException {

		if (inputLine.equals("")) {
			if (_notificationIndex < (_notificationsCount - 1)) {
				// There are more notifications to go
				_notificationIndex++;
			} else {
				if (_resources.size() > 0) {
					// We're expecting some embedded resources
					return RequestState.ReadingResourceHeaders;
				} else {
					return RequestState.EndOfRequest;
				}
			}
		} else {
			Map<String, String> notificationHeaders = _notificationsHeaders.get(_notificationIndex);
			if (notificationHeaders == null) {
				notificationHeaders = new HashMap<String, String>();
				_notificationsHeaders.put(_notificationIndex, notificationHeaders);
			}
			parseHeader(inputLine, notificationHeaders);
		}
		return RequestState.ReadingNotificationHeaders;
	}

	private RequestState parseResourceHeader(String inputLine) throws GntpException, IOException {
		if (inputLine.equals("")) {
			if (_resourceHeaders.size() == 0) {
				/*
				 * Unexpected blank line, but no current resource, so we'll
				 * gracefully ignore it. Seems to happen after resource data,
				 * but GNTP specification says data should be followed by a
				 * single blank line, not two. Single blank line is consumed in
				 * readResourceData()
				 */
			} else {
				// End of resource headers, start of data
				return RequestState.ReadingResourceData;
			}

		} else {
			// Still reading headers
			parseHeader(inputLine, _resourceHeaders);
		}
		return RequestState.ReadingResourceHeaders;
	}

	private RequestState readResourceData() throws IOException, DecryptionException {
		GrowlResource resource = _service.getRegistry().registerResource(_resourceHeaders);
		_resourceHeaders.clear();

		// Read in the file data, decrypt it and save it to a temporary location
		long length = resource.getLength();
		File cacheFile = resource.getCacheFile();
		if (!cacheFile.exists()) {
			// Read the bytes and save them to a file
			_socketReader.readAndDecryptBytesToCacheFile(length, _encryptionType, _initVector, _key, cacheFile);
			Log.i("GLT:" + _connectionID, "Created " + cacheFile.getAbsolutePath() + " as resource (" + cacheFile.length() + " bytes)");
			resource.tryResizeBitmap();
		} else {
			// Read the bytes but doen't save them anywhere
			_socketReader.readAndDecryptBytesToCacheFile(length, _encryptionType, _initVector, _key, null);
			Log.i("GLT:" + _connectionID, "Skipping duplicate resource (" + cacheFile.length() + " bytes)");
		}

		// Each resource is followed by a blank line
		String blankLine = _socketReader.readLine().trim();
		if (!blankLine.equals("")) {
			throw new IOException("Expected blank line, not: " + blankLine);
		}

		// Link the source file to the resource, register the resource and link
		// the resource to the notification
		_resources.put(resource.getIdentifier(), resource);

		_resourceIndex++;
		if (_resourceIndex >= _resources.size()) {
			return RequestState.EndOfRequest;
		}

		return RequestState.ReadingResourceHeaders;
	}

	private void doSubscribe() throws GntpException, MalformedURLException {
		throw new GntpException(GntpError.InternalServerError);
	}

	// Perform a notification
	private void doNotify() throws GntpException, MalformedURLException {
		// Find the registered application
		String name = _requestHeaders.get(Constants.HEADER_APPLICATION_NAME);
		GrowlApplication application = _service.getRegistry().getApplication(name);
		if (application == null) {
			throw new GntpException(GntpError.UnknownApplication);
		}

		// Find the registered notification type
		String typeName = _requestHeaders.get(Constants.HEADER_NOTIFICATION_NAME);
		NotificationType type = application.getNotificationType(typeName);
		if (type == null) {
			throw new GntpException(GntpError.UnknownNotification);
		}

		// Display the notification
		GrowlNotification notification = new GrowlNotification(type, _requestHeaders, _resources, _requestStartedMS);
		_service.displayNotification(notification);
	}

	// Register an application and its notification types
	private void doRegister() throws GntpException, MalformedURLException {
		String name = _requestHeaders.get(Constants.HEADER_APPLICATION_NAME);
		URL iconUrl = Utility.tryParseURL(_requestHeaders.get(Constants.HEADER_APPLICATION_ICON));
		GrowlApplication application = _service.getRegistry().registerApplication(name, iconUrl);

		for (int i = 0; i < _notificationsCount; i++) {
			// Register notification types
			Map<String, String> notificationHeaders = _notificationsHeaders.get(i);
			String typeName = notificationHeaders.get(Constants.HEADER_NOTIFICATION_NAME);
			String displayName = notificationHeaders.get(Constants.HEADER_NOTIFICATION_DISPLAY_NAME);
			boolean enabled = Boolean.valueOf(notificationHeaders.get(Constants.HEADER_NOTIFICATION_ENABLED));
			URL typeIconUrl = Utility.tryParseURL(notificationHeaders.get(Constants.HEADER_NOTIFICATION_ICON));

			application.registerNotificationType(typeName, displayName, enabled, typeIconUrl);
		}
	}

	private RequestState parseRequestLine(String inputLine) throws GntpException, IOException, DecryptionException {
		// Line can end with extraneous whitespace
		String requestLine = inputLine.trim();

		// GNTP/<version> <messageType> <encryptionAlgorithmID>[:<ivValue>][
		// <keyHashAlgorithmID>:<keyHash>.<salt>]
		String[] component = requestLine.split(Constants.FIELD_DELIMITER);
		if ((component.length < 3) || (component.length > 4)) {
			throw new GntpException(GntpError.InvalidRequest, "Expected 3 or 4 fields, found " + component.length
					+ " fields");
		}

		// Verify protocol and version are supported
		String[] protocolAndVersion = component[0].split("/");
		if (protocolAndVersion.length != 2) {
			throw new GntpException(GntpError.InvalidRequest, "Expected GNTP/1.0 protocol header");
		}
		if (!protocolAndVersion[0].equals(Constants.SUPPORTED_PROTOCOL)) {
			throw new GntpException(GntpError.UnknownProtocol);
		}
		if (!protocolAndVersion[1].equals(Constants.SUPPORTED_PROTOCOL_VERSION)) {
			throw new GntpException(GntpError.UnknownProtocolVersion);
		}

		// Message type
		_requestType = RequestType.fromString(component[1]);
		if (_requestType == null) {
			throw new GntpException(GntpError.InvalidRequest, "Unknown message type: " + component[1]);
		}

		// Encryption settings
		String[] encryptionTypeAndIV = component[2].split(":", 2);
		_encryptionType = EncryptionType.fromString(encryptionTypeAndIV[0]);
		if (_encryptionType == null) {
			throw new GntpException(GntpError.InvalidRequest, "Unsupported encryption type: " + _encryptionType);
		}
		String ivHex = (encryptionTypeAndIV.length == 2) ? encryptionTypeAndIV[1] : "";
		_initVector = Utility.hexStringToByteArray(ivHex);

		// Authentication hash
		if (component.length == 4) {
			String[] algoAndHash = component[3].split(":");
			if (algoAndHash.length != 2) {
				throw new GntpException(GntpError.NotAuthorized, "Unable to parse hash");
			}

			String algorithmName = algoAndHash[0];
			HashAlgorithm algorithm = HashAlgorithm.fromString(algorithmName);
			if (algorithm == null) {
				throw new GntpException(GntpError.InvalidRequest, "Unsupported hash type: " + algoAndHash[0]);
			}

			String hashDotSalt = algoAndHash[1];
			int dot = hashDotSalt.indexOf('.');
			if ((dot < 1) || (dot == hashDotSalt.length() - 1)) {
				throw new GntpException(GntpError.NotAuthorized, "Unable to parse hash");
			}
			String hash = hashDotSalt.substring(0, dot);
			String salt = hashDotSalt.substring(dot + 1);

			// Validate the hash
			_key = _service.getMatchingKey(algorithm, hash, salt);
			if (_key == null) {
				// We couldn't find a key that matches
				throw new GntpException(GntpError.NotAuthorized);
			}
		} else if (_service.requiresPassword()) {
			// The application didn't supply a password hash, but the registry
			// requires one
			Log.w("GLT:" + _connectionID, "Passwords are required, but this notification did not have one. Ignoring");
			throw new GntpException(GntpError.NotAuthorized);
		}

		// Decrypt the request headers
		_socketReader.decryptNextBlock(_encryptionType, _initVector, _key);
		return RequestState.ReadingRequestHeaders;
	}

	private String parseHeader(String inputLine, Map<String, String> headers) throws GntpException {
		String[] keyAndValue = inputLine.split(":", 2);
		if (keyAndValue.length != 2) {
			throw new GntpException(GntpError.InvalidRequest, "Unable to parse header: " + inputLine);
		}

		String key = keyAndValue[0];
		String value = keyAndValue[1].trim();
		headers.put(key, value);

		if (value.startsWith(Constants.RESOURCE_URI_PREFIX)) {
			String identifier = value.substring(Constants.RESOURCE_URI_PREFIX.length());
			Log.i("GLT:" + _connectionID, "Header " + key + " has a binary resource "
					+ identifier + " for its value");
			_resources.put(identifier, null);
		}

		return key;
	}

	private enum RequestState {
		Connected, ReadingRequestHeaders, ReadingNotificationHeaders, ReadingResourceHeaders, ReadingResourceData, EndOfRequest, ResponseSent
	}
}