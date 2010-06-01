package com.growlforandroid.client;

import java.io.*;
import java.net.*;
import java.nio.channels.SocketChannel;
import java.nio.charset.*;
import java.util.*;

import com.growlforandroid.common.*;
import com.growlforandroid.common.EncryptedChannelReader.DecryptionException;
import com.growlforandroid.gntp.*;

import android.util.Log;

public class GntpListenerThread extends Thread {
	private static final boolean SKIP_LOADING_RESOURCES = true;
	
	private final SocketAcceptor _acceptor;
	private final int _connectionID;
	private final Socket _socket;
	private final IGrowlRegistry _registry;
	private final SocketChannel _channel;
	private final EncryptedChannelReader _socketReader;
	private final ChannelWriter _socketWriter;
	private RequestState _currentState = RequestState.Connected;
	
	private RequestType _requestType;
	private EncryptionType _encryptionType;
	private byte[] _initVector;
	private byte[] _key;
	
	private Map<String, String> _requestHeaders = new HashMap<String, String>();
	private Map<String, GrowlResource> _resources = new HashMap<String, GrowlResource>();
	private GrowlResource _currentResource;
	private int _notificationsCount = 0;
	private int _notificationIndex = 0;
	private int _resourceIndex = 0;
	private Map<Integer, Map<String, String>> _notificationsHeaders = new HashMap<Integer, Map<String, String>>();
	
	public GntpListenerThread(SocketAcceptor socketAcceptor, int connectionID, IGrowlRegistry registry, SocketChannel channel)
		throws IllegalCharsetNameException, UnsupportedCharsetException, CharacterCodingException {
		
		super("GntpListenerThread");
		_acceptor = socketAcceptor;
		_connectionID = connectionID;
		_registry = registry;
		
		_channel = channel;
		_socketReader = new EncryptedChannelReader(_channel);
		_socketWriter = new ChannelWriter(_channel, Constants.CHARSET);
		
		_socket = channel.socket();
	}

	public void run() {
		try {
			int remotePort = _socket.getPort();
			Log.i("GNTPListenerThread.run[" + _connectionID + "]", "Connected to client on port " + remotePort);
			
			// Read lines from the socket until we've sent a response back	
			String inputLine;
			while ((_currentState != RequestState.ResponseSent) &&
					((inputLine = _socketReader.readLine()) != null)) {
				
				// Parse the input
				Log.i("GNTPListenerThread.run[" + _connectionID + "]", "Read line \"" + inputLine + "\"");
				try {
					// Parse the input
					switch (_currentState) {
						case Connected:
							// Parse first row of request: GNTP/1.0 REGISTER ...
							_currentState = parseRequestLine(inputLine);
							break;
							
						case ReadingRequestHeaders:
							// Parse request header rows: Application-Name: Growl for Android
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
							// Parse notification type header rows: Notification-Name: New Mail
							_currentState = parseNotificationHeader(inputLine);
							break;
					}
					
					// Are we ready to reply?
					if (_currentState == RequestState.EndOfRequest) {
						registerResources();
						
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
								// Notification hash is invalid, silently ignore this notification
								Log.w("GntpListenerThread.run[" + _connectionID + "]", "Ignoring notification with invalid hash");
								break;
								
							default:
								throw new GntpException(GntpError.InvalidRequest, "Unexpected message type");
						}

						// Send the response
						new Response(ResponseType.OK).write(_socketWriter);
						_currentState = RequestState.ResponseSent;
					}
					
				} catch (Exception x) {
					// Parsing error or something unexpected					
					Log.e("GntpListenerThread.run[" + _connectionID + "]", "Unexpected error while reading from socket", x);

					// Send the error response
					GntpError error = GntpError.getErrorFrom(x);
					new Response(ResponseType.Error, error).write(_socketWriter);
					_currentState = RequestState.ResponseSent;
				}
			}
			
			Log.i("GNTPListenerThread.run[" + _connectionID + "]", "No more input data, closing client connection from port " + remotePort);
			_channel.close();
			_socket.close();

		} catch (Exception x) {
			Log.e("GNTPListenerThread.run[" + _connectionID + "]", "Unexpected exception while reading from socket", x);
		}
		
		// Notify the SocketAcceptor that we're done
		Log.i("GNTPListenerThread.run[" + _connectionID + "]", "Connection closed");
		_acceptor.connectionClosed(this);
	}
	
	private RequestState parseRequestHeader(String inputLine) throws GntpException {
		if (inputLine.equals("")) {
			if (_requestType == RequestType.Register) {
				// Prepare to read the headers for each notification type
				_notificationsCount = Integer.valueOf(_requestHeaders.get(Constants.HEADER_NOTIFICATIONS_COUNT));
				return RequestState.ReadingNotificationHeaders;
			} else if (_resources.size() > 0) {
				// This request has one or more resources remaining
				Log.i("GntpListenerThread.parseRequestHeader[" + _connectionID + "]",
						"Reading " + _resources.size() + " resources...");
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

	private RequestState parseNotificationHeader(String inputLine)
		throws GntpException, IOException, DecryptionException{
		
		if (inputLine.equals("")) {
			if (_notificationIndex < (_notificationsCount - 1)) {
				// There are more notifications to go
				_notificationIndex ++;
				Log.i("GntpListenerThread.parseNotificationHeader[" + _connectionID + "]",
						"Preparing to read notification type " + _notificationIndex);
			} else {
				return RequestState.EndOfRequest;
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
			Log.i("GntpListenerThread.parseResourceHeader[" + _connectionID + "]",
					"End of resource " + _resourceIndex + " headers");
			return RequestState.ReadingResourceData;	
			
		} else {
			if (_currentResource == null) {
				_currentResource = new GrowlResource();
				Log.i("GntpListenerThread.parseResourceHeader[" + _connectionID + "]",
						"Start of resource " + _resourceIndex);
			}
			parseHeader(inputLine, _currentResource.Headers);
		}
		return RequestState.ReadingResourceHeaders;
	}
	
	private RequestState readResourceData()
		throws IOException, DecryptionException {
		
		// Read the bytes directly from the stream
		int length = _currentResource.getLength();
		
		if (SKIP_LOADING_RESOURCES) {
			Log.i("GntpListenerThread.readResourceData[" + _connectionID + "]",
					"Skipping " + length + " bytes of resource data");
			_socketReader.skipBytes(length);
			
		} else {
			Log.i("GntpListenerThread.readResourceData[" + _connectionID + "]",
					"Reading " + length + " bytes of resource data");
			byte[] data = _socketReader.readAndDecryptBytes(length, _encryptionType, _initVector, _key);

			// TODO: Store the data against the current resource
			
			_resources.put(_currentResource.getIdentifier(), _currentResource);
			_currentResource = null;
		}
		
		_resourceIndex ++;
		if (_resourceIndex >= _resources.size()) {
			Log.i("GntpListenerThread.readResourceData[" + _connectionID + "]", "End of resources");
			return RequestState.EndOfRequest;
		}

		return RequestState.ReadingResourceHeaders;
	}
	
	private void registerResources() {
		for(GrowlResource resource : _resources.values()) {
			if (resource != null) {
				_registry.registerResource(resource);
			}
		}
	}
		
	private void doSubscribe() throws GntpException, MalformedURLException {
		throw new GntpException(GntpError.InternalServerError);
	}

	// Perform a notification
	private void doNotify() throws GntpException, MalformedURLException {
		String name = _requestHeaders.get(Constants.HEADER_APPLICATION_NAME);
		GrowlApplication application = _registry.getApplication(name);
		if (application == null)
			throw new GntpException(GntpError.UnknownApplication);
		
		String typeName = _requestHeaders.get(Constants.HEADER_NOTIFICATION_NAME);
		NotificationType type = application.getNotificationType(typeName);
		if (type == null)
			throw new GntpException(GntpError.UnknownNotification);
		
		String ID = _requestHeaders.get(Constants.HEADER_NOTIFICATION_ID);
		String icon = _requestHeaders.get(Constants.HEADER_NOTIFICATION_ICON);
		URL iconUrl = (icon != null) ? new URL(icon) : null;
		
		String title = _requestHeaders.get(Constants.HEADER_NOTIFICATION_TITLE);
		String text = _requestHeaders.get(Constants.HEADER_NOTIFICATION_TEXT);
		
		_registry.displayNotification(type, ID, title, text, iconUrl);
	}

	// Register an application and its notification types
	private void doRegister() throws GntpException, MalformedURLException {
		String name = _requestHeaders.get(Constants.HEADER_APPLICATION_NAME);
		String icon = _requestHeaders.get(Constants.HEADER_APPLICATION_ICON);
		URL iconUrl = (icon != null) ? new URL(icon) : null;
		GrowlApplication application = _registry.registerApplication(name, iconUrl);
		
		for(int i=0; i<_notificationsCount; i++) {
			// Register notification types
			Map<String, String> notificationHeaders = _notificationsHeaders.get(i);
			String typeName = notificationHeaders.get(Constants.HEADER_NOTIFICATION_NAME);
			String displayName = notificationHeaders.get(Constants.HEADER_NOTIFICATION_DISPLAY_NAME);
			boolean enabled = Boolean.valueOf(notificationHeaders.get(Constants.HEADER_NOTIFICATION_ENABLED));
			String typeIcon = notificationHeaders.get(Constants.HEADER_NOTIFICATION_ICON);
			URL typeIconUrl = (typeIcon != null) ? new URL(typeIcon) : null;
			
			application.registerNotificationType(typeName, displayName, enabled, typeIconUrl);
		}
	}

	private RequestState parseRequestLine(String inputLine)
		throws GntpException, IOException, DecryptionException {
		
		// GNTP/<version> <messagetype> <encryptionAlgorithmID>[:<ivValue>][ <keyHashAlgorithmID>:<keyHash>.<salt>]
		String[] component = inputLine.split(Constants.FIELD_DELIMITER);
		if ((component.length < 3) || (component.length > 4))
			throw new GntpException(GntpError.InvalidRequest, "Expected 3 or 4 fields, found " + component.length + " fields");
		
		// Verify protocol and version are supported
		String[] protocolAndVersion = component[0].split("/");
		if (protocolAndVersion.length != 2)
			throw new GntpException(GntpError.InvalidRequest, "Expected GNTP/1.0 protocol header");
		if (!protocolAndVersion[0].equals(Constants.SUPPORTED_PROTOCOL))
			throw new GntpException(GntpError.UnknownProtocol);
		if (!protocolAndVersion[1].equals(Constants.SUPPORTED_PROTOCOL_VERSION))
			throw new GntpException(GntpError.UnknownProtocolVersion);

		// Message type
		_requestType = RequestType.fromString(component[1]);
		if (_requestType == null)
			throw new GntpException(GntpError.InvalidRequest, "Unknown message type: " + component[1]);
		
		// Encryption settings
		String[] encryptionTypeAndIV = component[2].split(":", 2);
		_encryptionType = EncryptionType.fromString(encryptionTypeAndIV[0]);
		if (_encryptionType == null)
			throw new GntpException(GntpError.InvalidRequest, "Unsupported encryption type: " + _encryptionType);
		Log.i("GntpListenerThread.parseRequestLine[" + _connectionID + "]",
				"Encryption Type: " + _encryptionType);
		String ivHex = (encryptionTypeAndIV.length == 2) ? encryptionTypeAndIV[1] : "";
		_initVector = Utility.hexStringToByteArray(ivHex);
		Log.i("GntpListenerThread.parseRequestLine[" + _connectionID + "]",
				"Encryption IV:   " + ((encryptionTypeAndIV.length == 2) ? ivHex : "(none)"));
		
		// Authentication hash
		if (component.length == 4) {
			String[] algoAndHash = component[3].split(":");
			if (algoAndHash.length != 2)
				throw new GntpException(GntpError.NotAuthorized, "Unable to parse hash");
			
			String algorithmName = algoAndHash[0];
			HashAlgorithm algorithm = HashAlgorithm.fromString(algorithmName);
			if (algorithm == null)
				throw new GntpException(GntpError.InvalidRequest, "Unsupported hash type: " + algoAndHash[0]);
			
			String hashDotSalt = algoAndHash[1];
			int dot = hashDotSalt.indexOf('.');
			if ((dot < 1) || (dot == hashDotSalt.length() - 1))
				throw new GntpException(GntpError.NotAuthorized, "Unable to parse hash");
			String hash = hashDotSalt.substring(0, dot);
			String salt = hashDotSalt.substring(dot + 1);
			
			// Validate the hash
			_key = _registry.getMatchingKey(algorithm, hash, salt);
			if (_key == null) {
				// We couldn't find a key that matches, ignore this notification
				Log.i("GntpListenerThread.parseRequestLine[" + _connectionID + "]",
						"Encryption Key:  (none)");
				throw new GntpException(GntpError.NotAuthorized);
			} else {
				Log.i("GntpListenerThread.parseRequestLine[" + _connectionID + "]",
						"Encryption Key:  " + Utility.getHexStringFromByteArray(_key));
			}
		} else if (_registry.requiresPassword()) {
			// The application didn't supply a password hash, but the registry requires one
			Log.w("GntpListenerThread.parseRequestLine[" + _connectionID + "]",
					"Passwords are required, but this notification did not have one. Ignoring");
			throw new GntpException(GntpError.NotAuthorized);
		}

		// Decrypt the request headers
		_socketReader.decryptNextBlock(_encryptionType, _initVector, _key);
		return RequestState.ReadingRequestHeaders;
	}

	private String parseHeader(String inputLine, Map<String, String> headers) throws GntpException {	
		String[] keyAndValue = inputLine.split(":", 2);
		if (keyAndValue.length != 2)
			throw new GntpException(GntpError.InvalidRequest, "Unable to parse header: " + inputLine);
		
		String key = keyAndValue[0];
		String value = keyAndValue[1].trim();
		headers.put(key, value);
		
		if (value.startsWith(Constants.RESOURCE_URI_PREFIX)) {
			String identifier = value.substring(Constants.RESOURCE_URI_PREFIX.length());
			Log.i("GntpListenerThread.parseHeader[" + _connectionID + "]",
					"Header " + key + " has a binary resource " + identifier + " for its value");
			_resources.put(identifier, null);
		}
		
		return key;
	}
	
	private enum RequestState {
		Connected,
		ReadingRequestHeaders,
		ReadingNotificationHeaders,
		ReadingResourceHeaders,
		ReadingResourceData,
		EndOfRequest,
		ResponseSent
	}
}