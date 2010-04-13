package bitraptor;

import java.util.*;
import java.net.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import org.klomp.snark.bencode.*;

public class Torrent
{
	private enum State { STARTED, RUNNING, STOPPED, COMPLETED };
	
	private static byte[] protocolName = {'B', 'i', 't', 'T', 'o', 'r', 'r', 'e', 'n', 't', ' ', 'p', 'r', 'o', 't', 'o', 'c', 'o', 'l'};
	
	private Info info = null; 
	private int port;
	private byte[] peerID;
	private String trackerID = null;
	private Selector handshakeSelect;
	private Selector select;
	private ArrayList<Peer> peers;
	private State state = State.STARTED;
	private ByteBuffer handshakeMsg;
	
	/**
		Initializes the Torrent based on the information from the file.
		
		@param info Contains torrent characteristics
	*/
	public Torrent(Info info, int port)
	{
		this.port = port;
		this.info = info;
		
		peers = new ArrayList<Peer>();
	
		//Creating a random peer ID (BRXXX...)
		peerID = new byte[20];
		(new Random()).nextBytes(peerID);
		
		peerID[0] = (byte)'B';
		peerID[1] = (byte)'R';
		peerID[2] = (byte)'-';
		
		//Change the random bytes into numeric characters
		for (int b = 3; b < 20; b++)
		{
			peerID[b] = (byte)(((peerID[b] & 0xFF) % 10) + 48); 
		}
		
		//Creating the handshake message
		handshakeMsg = ByteBuffer.allocateDirect(68);
		handshakeMsg.put((byte)19).put(protocolName).putDouble(0.0).put(info.getInfoHash()).put(peerID);
		
		//Setting up selectors
		try
		{
			handshakeSelect = Selector.open();
			select = Selector.open();
		}
		catch (Exception e)
		{
			System.out.println("ERROR: Could not open selectors for use in torrent");
		}
	}
	
	/**
		Starts downloading the torrent
	*/
	public void start()
	{
		//Starting up the announcer (runs immediately and then schedules a next run)
		(new TorrentAnnouncer(this)).run();
		
		//TODO: OTHER STUFF???
		//	-Open up output files for writing (Probably best to take care of this in the Info portions, although maybe
		//	info would no longer be the best name... Have write() calls in there too, which is great for multifile
		//	to just have one write call which simplifies stuff out here. Only open once a piece is written!
		//
		//	-We may want to schedule other periodic tasks too, to handle upload slot assignments etc...
		//	(seems like start() may be ALL periodic task initialization haha)
	}
		
	/**
		Attempts to connect to the peer, and if not an incoming peer, perform a handshake with it before 
		adding it to the list of peers
	
		@param peer The peer to add
	*/
	public void addPeer(Peer peer, boolean incoming)
	{
		//Making sure that the peer is not already in the list
		if (!peers.contains(peer))
		{
			try
			{
				//Connect to the peer via TCP
				peer.connect();
				
				//Sending a handshake message to the peer [[[TODO: ADD QUEUES]]]
				//peer.write(handshakeMsg);
				//handshakeMsg.position(0);
				
				//Incoming Peer: Already received a valid handshake, so place it in main selector with handshake message in its write queue
				if (incoming)
				{
				
				}
				//Outgoing Peer: Waiting on valid handshake, so place it in handshake selector with handshake message in its write queue
				else
				{
				
				}
			}
			catch (IOException e)
			{
				return;
			}
			
			//Adding the peer to the list
			peers.add(peer);
			
			System.out.println("[PEER] " + peer.getSockAddr());
		}
	}
	
	private class TorrentAnnouncer extends TimerTask
	{
		private Torrent toAnnounce;
		
		public TorrentAnnouncer(Torrent toAnnounce)
		{
			this.toAnnounce = toAnnounce;
		}
	
		/**
			Encodes an array of bytes into a valid URL string representation
		
			@param data Array of bytes
		
			@return Encoded representation String
		*/
		private String encode(byte[] data)
		{
			String encoded = "";
		
			for (int b = 0; b < data.length; b++)
			{
				encoded += "%" + (((data[b] & 0xF0) == 0) ? ("0" + Integer.toHexString(data[b] & 0xFF)) : Integer.toHexString(data[b] & 0xFF));
			}
		
			return encoded;
		}
		
		/**
			Schedules another announce to occur after a certain number of seconds
		
			@param seconds Number of seconds before next ammounce
		*/
		private void schedule(int seconds)
		{
			(new Timer(false)).schedule(new TorrentAnnouncer(toAnnounce), seconds * 1000);
		}
		
		/**
			Attempts to contact trackers in the announce URL list in order. Upon a successful response, it
			parses it and handles new peer information.
			
			NOTE: Always schedules itself to run again after a certain number of seconds.
		*/
		public void run()
		{
			byte[] response = null;
		
			//Going through all the announce URLs (if needed)
      		for (URL announceURL : info.getAnnounceUrls())
      		{
				//Setting up the query URL
				String query = "?info_hash=" + encode(info.getInfoHash()) + "&peer_id=" + encode(peerID) + "&port=" + port + 
				"&uploaded=0&downloaded=0&left=" + info.getFileLength() + "&compact=0&no_peer_id=0";
				
				//Including event if not in RUNNING state
				if (state != State.RUNNING)
				{
					query += "&event=" + state.toString().toLowerCase();
				}
			
				//Including tracker ID if it was set by the tracker previously
				if (trackerID != null)
				{
					query += "&trackerid=" + trackerID;
				}
		
				try
				{
					//Initializing the connection
					URL trackerQueryURL = new URL(announceURL.toString() + query);
					HttpURLConnection conn = (HttpURLConnection)(trackerQueryURL.openConnection());
					conn.setRequestMethod("GET");
					conn.setDoOutput(true);
					conn.connect();
		
					//Reading the response from the tracker
					InputStream istream = conn.getInputStream();
					response = new byte[256];
					int totalBytesRead = 0;
					int bytesRead = 0;
				
					while ((bytesRead = istream.read(response, totalBytesRead, 256)) != -1)
					{
						totalBytesRead += bytesRead;
						
						//Done reading, so remove extra bytes from end of response
						if (bytesRead < 256)
						{
							response = Arrays.copyOf(response, totalBytesRead);
						}
						//Set up response for next read
						else
						{
							response = Arrays.copyOf(response, totalBytesRead + 256);
						}
					}
				
					istream.close();
		
					//Disconnecting from the tracker
					conn.disconnect();
				}
				//Move onto the next announce URL
				catch (Exception e)
				{
					continue;
				}
			}
			
			//No response from any of the announce URLs
			if (response == null)
			{
				System.out.println("ERROR: Couldn't announce");
				System.out.println("Will retry in 30 seconds...");
				schedule(30);
				return;
			}
			
			//Parsing the response from the tracker
			try
			{
				BDecoder decoder = new BDecoder(new ByteArrayInputStream(response));
				Map<String, BEValue> replyDictionary = decoder.bdecode().getMap();
				
				//Announce failed
				if (replyDictionary.containsKey("failure reason"))
				{
					String reason = new String(replyDictionary.get("failure reason").getBytes());
					System.out.println("Announce Failed: " + reason);
					
					System.out.println("Will retry in 30 seconds...");
					schedule(30);
					return;
				}
				
				int interval = replyDictionary.get("interval").getInt();
				int seeders = replyDictionary.get("complete").getInt();
				int leechers = replyDictionary.get("incomplete").getInt();
				
				System.out.println("Seeders: " + seeders);
				System.out.println("Leechers: " + leechers);
				
				//Tracker ID is an optional field
				if (replyDictionary.containsKey("tracker id"))
				{
					trackerID = new String(replyDictionary.get("tracker id").getBytes());
				}
				
				//Getting peer information via dictionaries (Throws exception if tracker sent in binary format)
				try
				{
					List<BEValue> peersDictionaries = replyDictionary.get("peers").getList();
					
					for (BEValue peerDictionary : peersDictionaries)
					{
						Map<String, BEValue> peerDictionaryMap = peerDictionary.getMap();
						
						byte[] peerID = peerDictionaryMap.get("peer id").getBytes();
						String IPAddr = peerDictionaryMap.get("ip").getString();
						int port = peerDictionaryMap.get("port").getInt();
						
						addPeer(new Peer(peerID, IPAddr, port), false);
					}
				}
				//Getting peer information via binary format
				catch (InvalidBEncodingException e)
				{
					byte[] peers = replyDictionary.get("peers").getBytes();
					
					for (int c = 0; c < peers.length; c += 6)
					{
						String IPAddr = Integer.toString((int)peers[c] & 0xFF) + "." 
							+ Integer.toString((int)peers[c + 1] & 0xFF) + "."
							+ Integer.toString((int)peers[c + 2] & 0xFF) + "."
							+ Integer.toString((int)peers[c + 3] & 0xFF);
						int port = (((peers[c + 4] & 0xFF) << 8) + (peers[c + 5] & 0xFF)) & 0xFFFF;
						
						addPeer(new Peer(new byte[20], IPAddr, port), false);
					}
				}
				
				//Scheduling another announce after the specified time interval
				System.out.println("Announce Successful! " + interval + " seconds until next announce");
				schedule(interval);
			}
			//Invalid response from the tracker (Could not be parsed)
			catch (Exception e)
			{
				System.out.println("ERROR: Received an invalid response from the tracker");
				System.out.println("Will retry in 30 seconds...");
				e.printStackTrace();
				schedule(30);
			}
		}
	}
}
