package com.lekebilen.quasseldroid;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.net.SocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import android.content.SharedPreferences;

import com.lekebilen.quasseldroid.qtcomm.DataStreamVersion;
import com.lekebilen.quasseldroid.qtcomm.QDataInputStream;
import com.lekebilen.quasseldroid.qtcomm.QDataOutputStream;
import com.lekebilen.quasseldroid.qtcomm.QMetaType;
import com.lekebilen.quasseldroid.qtcomm.QMetaTypeRegistry;
import com.lekebilen.quasseldroid.qtcomm.QVariant;


public class CoreConnection {
	private enum RequestType {
		Invalid(0),
	    Sync(1),
	    RpcCall(2),
	    InitRequest(3),
	    InitData(4),
	    HeartBeat(5),
	    HeartBeatReply(6);
	    
        int value;
        RequestType(int value){
        	this.value = value;
        }
        public int getValue(){
        	return value;
        }
        
        public static RequestType getForVal(int val) {
        	for (RequestType type: values()) {
        		if (type.value == val)
        			return type;
        	}
        	return Invalid;
        }
	}
	
	private QDataOutputStream outStream;
	private QDataInputStream inStream;
	
	private Map<Integer, Buffer> buffers;
	private Map<String, IrcUser> users;
	
	public Map<String, IrcUser> getUsers() {
		return users;
	}

	public static void main(String[] args) {
		try {
			CoreConnection conn = new CoreConnection("localhost", 4242, "test", "test", null);
		} catch (UnknownHostException e) {
			System.err.println("Unknown host!");
		} catch (IOException e) {
			e.printStackTrace();
		} catch (GeneralSecurityException e) {
			System.err.println("Security error!");
			e.printStackTrace();
		}
	}
	private SharedPreferences settings;

	
	public CoreConnection(String host, int port, String username, String password, SharedPreferences settings)
		throws UnknownHostException, IOException, GeneralSecurityException {
			this.settings = settings;
			users = new HashMap<String, IrcUser>();
			
			// START CREATE SOCKETS
			SocketFactory factory = (SocketFactory)SocketFactory.getDefault();
			Socket socket = (Socket)factory.createSocket(host, port);
			outStream = new QDataOutputStream(socket.getOutputStream());
			// END CREATE SOCKETS 

			
			// START CLIENT INFO
			Map<String, QVariant<?>> initial = new HashMap<String, QVariant<?>>();
			
			DateFormat dateFormat = new SimpleDateFormat("MMM dd yyyy HH:mm:ss");
			Date date = new Date();
			initial.put("ClientDate", new QVariant<String>(dateFormat.format(date), QVariant.Type.String));
			initial.put("UseSsl", new QVariant<Boolean>(settings.getBoolean("useSSL", true), QVariant.Type.Bool));
			initial.put("ClientVersion", new QVariant<String>("v0.6.1 (dist-<a href='http://git.quassel-irc.org/?p=quassel.git;a=commit;h=611ebccdb6a2a4a89cf1f565bee7e72bcad13ffb'>611ebcc</a>)", QVariant.Type.String));
			initial.put("UseCompression", new QVariant<Boolean>(false, QVariant.Type.Bool));
			initial.put("MsgType", new QVariant<String>("ClientInit", QVariant.Type.String));
			initial.put("ProtocolVersion", new QVariant<Integer>(10, QVariant.Type.Int));
			
			sendQVariantMap(initial);
			// END CLIENT INFO
			
			
			// START CORE INFO
			inStream = new QDataInputStream(socket.getInputStream());
			Map<String, QVariant<?>> reply = readQVariantMap();
			System.out.println("CORE INFO: ");
			for (String key : reply.keySet()) {
				System.out.println("\t" + key + " : " + reply.get(key));
			}
			// TODO: We should check that the core is new and dandy here. 
			// END CORE INFO

			
			// START SSL CONNECTION
			if (settings.getBoolean("useSSL", true)) {
				SSLContext sslContext = SSLContext.getInstance("TLS");
				TrustManager[] trustManagers = new TrustManager [] { new CustomTrustManager() };
				sslContext.init(null, trustManagers, null);
				SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
				SSLSocket sslSocket = (SSLSocket) sslSocketFactory.createSocket(socket, host, port, true);
				sslSocket.setEnabledProtocols(new String[] {"SSLv3"});
	
	
				sslSocket.setUseClientMode(true);
				sslSocket.startHandshake();
				inStream = new QDataInputStream(sslSocket.getInputStream());
				outStream = new QDataOutputStream(sslSocket.getOutputStream());
			}
			// FINISHED SSL CONNECTION
			
			
			// START LOGIN
			Map<String, QVariant<?>> login = new HashMap<String, QVariant<?>>();
			login.put("MsgType", new QVariant<String>("ClientLogin", QVariant.Type.String));
			login.put("User", new QVariant<String>(username, QVariant.Type.String));
			login.put("Password", new QVariant<String>(password, QVariant.Type.String));
			sendQVariantMap(login);
			// FINISH LOGIN
			
			
			// START LOGIN ACK 
			reply = readQVariantMap();
			if (!reply.get("MsgType").toString().equals("ClientLoginAck"))
				throw new GeneralSecurityException("Invalid password?");
			// END LOGIN ACK

			
			// START SESSION INIT
			reply = readQVariantMap();
			System.out.println("SESSION INIT: ");
			for (String key : reply.keySet()) {
				System.out.println("\t" + key + " : " + reply.get(key));
			}
			
			Map<String, QVariant<?>> sessionState = (Map<String, QVariant<?>>) reply.get("SessionState").getData();
			List<QVariant<?>> bufferInfos = (List<QVariant<?>>) sessionState.get("BufferInfos").getData();
			buffers = new HashMap<Integer, Buffer>();
			for (QVariant<?> bufferInfoQV: bufferInfos) {
				BufferInfo bufferInfo = (BufferInfo)bufferInfoQV.getData();
				buffers.put(bufferInfo.id, new Buffer(bufferInfo));
			}
			// END SESSION INIT
			
			// Now the fun part starts, where we play signal proxy
			
			// START SIGNAL PROXY INIT
			sendInitRequest("BacklogManager", "");
			sendInitRequest("Network", "1");
			sendInitRequest("BufferSyncer", "");
			
			List<QVariant<?>> packedFunc = new LinkedList<QVariant<?>>();
			packedFunc.add(new QVariant<Integer>(RequestType.Sync.getValue(), QVariant.Type.Int));
			packedFunc.add(new QVariant<String>("BufferSyncer", QVariant.Type.String));
			packedFunc.add(new QVariant<String>("", QVariant.Type.String));
			packedFunc.add(new QVariant<String>("requestSetLastSeenMsg", QVariant.Type.String));
			packedFunc.add(new QVariant<Integer>(1, "BufferId"));
			packedFunc.add(new QVariant<Integer>(1, "MsgId"));
			sendQVariantList(packedFunc);
			
			
			ReadThread readThread = new ReadThread(this);
			readThread.start();
			
			
			// Apparently the client doesn't send heartbeats?
			/*TimerTask sendPingAction = new TimerTask() {
				public void run() {
					
				}
			};*/
			
			// END SIGNAL PROXY
	}
	
	/**
	 * Returns list of buffers in use. 
	 * @return
	 */
	public Collection<Buffer> getBuffers() {
		return buffers.values();
	}
	
	private class ReadThread extends Thread {
		boolean running = false;
		CoreConnection parent;
		
		public ReadThread(CoreConnection parent) {
			this.parent = parent;
		}
		
		public void run() {
			this.running = true;
			
			List<QVariant<?>> packedFunc;
			while (running) {
				try {
					packedFunc = readQVariantList();
				} catch (IOException e) {
					running = false;//FIXME: handle this properly?
					System.err.println("IO error!");	
					e.printStackTrace();
					return;
				}
				RequestType type = RequestType.getForVal((Integer)packedFunc.remove(0).getData());
				String name;
				switch (type) {
				case HeartBeat:
					System.out.println("Got heartbeat");
					break;
				case InitData:
					name = new String(((ByteBuffer)packedFunc.remove(0).getData()).array());
					if (name.equals("Network")) {
						// Do nothing, for now
					} else if (name.equals("BufferSyncer")) {
						packedFunc.remove(0); // Object name, not used
						List<QVariant<?>> lastSeen = (List<QVariant<?>>) ((Map<String, QVariant<?>>)packedFunc.get(0).getData()).get("LastSeenMsg").getData();
						for (int i=0; i<lastSeen.size()/2; i++) {
							int bufferId = (Integer)lastSeen.remove(0).getData();
							int msgId = (Integer)lastSeen.remove(0).getData();
							if (buffers.containsKey(bufferId)) // We only care for buffers we have open
								buffers.get(bufferId).setLastSeenMessage(msgId);
						}
						List<QVariant<?>> markerLines = (List<QVariant<?>>) ((Map<String, QVariant<?>>)packedFunc.get(0).getData()).get("MarkerLines").getData();
						for (int i=0; i<lastSeen.size()/2; i++) {
							int bufferId = (Integer)lastSeen.remove(0).getData();
							int msgId = (Integer)lastSeen.remove(0).getData();
							if (buffers.containsKey(bufferId))
								buffers.get(bufferId).setMarkerLineMessage(msgId);
						}
						for (int buffer: buffers.keySet()) {
							requestBacklog(buffer, buffers.get(buffer).getLastSeenMessage());
						}
					} else if (name.equals("IrcUser")) {
						IrcUser user = new IrcUser();
						user.name = (String) packedFunc.remove(0).getData();
						Map<String, QVariant<?>> map = (Map<String, QVariant<?>>) packedFunc.remove(0).getData();
						user.away = (Boolean) map.get("away").getData();
						user.awayMessage = (String) map.get("awayMessage").getData();
						user.ircOperator = (String) map.get("ircOperator").getData();
						user.nick = (String) map.get("nick").getData();
						user.channels = (List<String>) map.get("channels").getData();
						users.put(user.nick, user);
					} else {
						System.out.println("InitData: " + name);
					}
					break;
				case Sync:
					String className = packedFunc.remove(0).toString();
					packedFunc.remove(0); // object name, we don't really care
					String function = packedFunc.remove(0).toString();
					
					if (className.equals("BacklogManager") && function.equals("receiveBacklog")) {
						int buffer = (Integer) packedFunc.remove(0).getData();
						packedFunc.remove(0); // first
						packedFunc.remove(0); // last
						packedFunc.remove(0); // limit
						packedFunc.remove(0); // additional
						for (QVariant<?> message: (List<QVariant<?>>)(packedFunc.remove(0).getData())) {
							buffers.get(buffer).addBacklog((Message) message.getData());
						}
					} else if (className.equals("Network") && function.equals("addIrcUser")) {
						String nick = (String) packedFunc.remove(0).getData();
						try {
							sendInitRequest("IrcUser", "1/" + nick);
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					} else {
						System.out.println("Sync request: " + className + "::" + function);
					}

					break;
				case RpcCall:
					String functionName = packedFunc.remove(0).toString();
//					int buffer = functionName.charAt(0);
//					functionName = functionName.substring(1);
					if (functionName.equals("2displayMsg(Message)")) {
						Message message = (Message) packedFunc.remove(0).getData();
						buffers.get(message.bufferInfo.id).addBacklog(message);
					} else {
						System.out.println("RpcCall: " + functionName + " (" + packedFunc + ").");
					}
					break;
				default:
					System.out.println(type);
				}
			}
		}
	}

	
	private void sendQVariant(QVariant<?> data) throws IOException {
		// See how much data we're going to send
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		QDataOutputStream bos = new QDataOutputStream(baos);
		QMetaTypeRegistry.serialize(QMetaType.Type.QVariant, bos, data);
		
		// Tell the other end how much data to expect
		outStream.writeUInt(bos.size(), 32);
		
		// Sanity check, check that we can decode our own stuff before sending it off
		QDataInputStream bis = new QDataInputStream(new ByteArrayInputStream(baos.toByteArray()));
		QMetaTypeRegistry.instance().getTypeForId(QMetaType.Type.QVariant.getValue()).getSerializer().unserialize(bis, DataStreamVersion.Qt_4_2);
		
		// Send data 
		QMetaTypeRegistry.serialize(QMetaType.Type.QVariant, outStream, data);
	}
	
	private void sendQVariantMap(Map<String, QVariant<?>> data) throws IOException {
		QVariant<Map<String, QVariant<?>>> bufstruct = new QVariant<Map<String, QVariant<?>>>(data, QVariant.Type.Map);
		sendQVariant(bufstruct);
	}
	
	private void sendQVariantList(List<QVariant<?>> data) throws IOException {
		QVariant<List<QVariant<?>>> bufstruct = new QVariant<List<QVariant<?>>>(data, QVariant.Type.List);
		sendQVariant(bufstruct);
	}
	
	private Map<String, QVariant<?>> readQVariantMap() throws IOException {
		long len = inStream.readUInt(32);
		QVariant <Map<String, QVariant<?>>> v = (QVariant <Map<String, QVariant<?>>>)QMetaTypeRegistry.unserialize(QMetaType.Type.QVariant, inStream);

		Map<String, QVariant<?>>ret = (Map<String, QVariant<?>>)v.getData();
		
		return ret;
	}
	
	private List<QVariant<?>> readQVariantList() throws IOException {	
		long len = inStream.readUInt(32);
		QVariant <List<QVariant<?>>> v = (QVariant <List<QVariant<?>>>)QMetaTypeRegistry.unserialize(QMetaType.Type.QVariant, inStream);

		List<QVariant<?>>ret = (List<QVariant<?>>)v.getData();
		
		return ret;
	}
	
	private void sendInitRequest(String className, String objectName) throws IOException {
		List<QVariant<?>> packedFunc = new LinkedList<QVariant<?>>();
		packedFunc.add(new QVariant<Integer>(RequestType.InitRequest.getValue(), QVariant.Type.Int));
		packedFunc.add(new QVariant<String>(className, QVariant.Type.String));
		packedFunc.add(new QVariant<String>(objectName, QVariant.Type.String));
		sendQVariantList(packedFunc);
	}
	
	private void requestBacklog(int buffer, int first) {
		requestBacklog(buffer, first, -1);
	}
	
	private void requestBacklog(int buffer, int firstMsg, int lastMsg) {
		List<QVariant<?>> retFunc = new LinkedList<QVariant<?>>();
		retFunc.add(new QVariant<Integer>(RequestType.Sync.getValue(), QVariant.Type.Int));
		retFunc.add(new QVariant<String>("BacklogManager", QVariant.Type.String));
		retFunc.add(new QVariant<String>("", QVariant.Type.String));
		retFunc.add(new QVariant<String>("requestBacklog", QVariant.Type.String));
		retFunc.add(new QVariant<Integer>(buffer, "BufferId"));
		retFunc.add(new QVariant<Integer>(firstMsg, "MsgId"));
		retFunc.add(new QVariant<Integer>(lastMsg, "MsgId"));
		retFunc.add(new QVariant<Integer>(Config.backlogLimit, QVariant.Type.Int));
		retFunc.add(new QVariant<Integer>(Config.backlogAdditional, QVariant.Type.Int));
		
		try {
			sendQVariantList(retFunc);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	private void sendMessage(int buffer, String message) {
		List<QVariant<?>> retFunc = new LinkedList<QVariant<?>>();
		retFunc.add(new QVariant<Integer>(RequestType.RpcCall.getValue(), QVariant.Type.Int));
		retFunc.add(new QVariant<String>("2sendInput(BufferInfo,QString)", QVariant.Type.String));
		retFunc.add(new QVariant<BufferInfo>(buffers.get(buffer).getInfo(), "BufferInfo"));
		retFunc.add(new QVariant<String>("/SAY " + message, QVariant.Type.String));	
		try {
			sendQVariantList(retFunc);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	private class CustomTrustManager implements javax.net.ssl.X509TrustManager {
	     /*
	      * The default X509TrustManager returned by SunX509.  We'll delegate
	      * decisions to it, and fall back to the logic in this class if the
	      * default X509TrustManager doesn't trust it.
	      */
	     X509TrustManager defaultTrustManager;

	     CustomTrustManager() throws GeneralSecurityException {
	         // create a "default" JSSE X509TrustManager.

	         KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
	         //ks.load(new FileInputStream("trustedCerts"),
	         //    "passphrase".toCharArray());

	         TrustManagerFactory tmf = TrustManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
	         tmf.init(ks);

	         TrustManager tms [] = tmf.getTrustManagers();

	         /*
	          * Iterate over the returned trustmanagers, look
	          * for an instance of X509TrustManager.  If found,
	          * use that as our "default" trust manager.
	          */
	         for (int i = 0; i < tms.length; i++) {
	             if (tms[i] instanceof X509TrustManager) {
	                 defaultTrustManager = (X509TrustManager) tms[i];
	                 return;
	             }
	         }

	         /*
	          * Find some other way to initialize, or else we have to fail the
	          * constructor.
	          */
	         throw new GeneralSecurityException("Couldn't initialize");
	     }

	     /*
	      * Delegate to the default trust manager.
	      */
	     public void checkClientTrusted(X509Certificate[] chain, String authType)
	                 throws CertificateException {
	         try {
	             defaultTrustManager.checkClientTrusted(chain, authType);
	         } catch (CertificateException excep) {

	         }
	     }

	     /*
	      * Delegate to the default trust manager.
	      */
	     public void checkServerTrusted(X509Certificate[] chain, String authType)
	                 throws CertificateException {
	         try {
	             defaultTrustManager.checkServerTrusted(chain, authType);
	         } catch (CertificateException excep) {
	        	 String hashedCert = hash(chain[0].getEncoded());
	        	 if (CoreConnection.this.settings.contains("certificate")) {
	        		 if (!CoreConnection.this.settings.getString("certificate", "lol").equals(hashedCert)) {
	        			 throw new CertificateException();
	        		 }
	        	 } else {
	        		 System.out.println("Storing new certificate: " + hashedCert);
	        		 CoreConnection.this.settings.edit().putString("certificate", hashedCert).commit();
	        	 }
	         }
	     }
	     
	     private String hash(byte[] s) {
	    	    try {
	    	        MessageDigest digest = java.security.MessageDigest.getInstance("SHA1");
	    	        digest.update(s);
	    	        byte messageDigest[] = digest.digest();
	    	        StringBuffer hexString = new StringBuffer();
	    	        for (int i=0; i<messageDigest.length; i++)
	    	            hexString.append(Integer.toHexString(0xFF & messageDigest[i]));
	    	        return hexString.toString();	    	        
	    	    } catch (NoSuchAlgorithmException e) {
	    	        e.printStackTrace();
	    	    }
	    	    return "";
	    	}


	     /*
	      * Merely pass this through.
	      */
	     public X509Certificate[] getAcceptedIssuers() {
	         return defaultTrustManager.getAcceptedIssuers();
	     }
	}
}