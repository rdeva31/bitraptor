package bitraptor;

import java.util.*;
import java.net.*;
import java.io.*;
import org.klomp.snark.bencode.*;

public class Torrent
{
	private Info info = null; 
	private int port;
	byte[] peerID;
	String trackerID = null;
	private ArrayList<Peer> peers;
	
	/**
		Initializes the Torrent based on the information from the file.
		
		@param info Contains torrent characteristics
		
		@throws NullPointerException Thrown if info == null
	*/
	public Torrent(Info info, int port) throws NullPointerException
	{
		if (info == null)
		{
			throw new NullPointerException("Info can't be null");
		}
		
		this.port = port;
		this.info = info;
		
		//Creating a random peer ID (BRXXX...)
		peerID = new byte[20];
		(new Random()).nextBytes(peerID);
		peerID[0] = (byte)'B';
		peerID[1] = (byte)'R';
	}
	
	/**
		Starts downloading the torrent
	*/
	public void start()
	{
		//Initializing peers list
		peers = new ArrayList<Peer>();
	
		//Contacting the tracker for the first time
		(new Timer(false)).schedule(new TorrentAnnouncer(this), 0);
		
		//TODO: OTHER STUFF???
		//	-Open up output files for writing (Probably best to take care of this in the Info portions, although maybe
		//	info would no longer be the best name... Have write() calls in there too, which is great for multifile
		//	to just have one write call which simplifies stuff out here. Only open once a piece is written!
		//
		//	-We may want to schedule other periodic tasks too, to handle upload slot assignments etc...
		//	(seems like start() may be ALL periodic task initialization haha)
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
				encoded += "%" + (((data[b] & 0xF0) == 0) ? ("0" + Integer.toHexString(data[b] & 0xFF)) : 
							Integer.toHexString(data[b] & 0xFF));
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
			Adds a peer to the list of peers if not already present (Tracker may send same peer(s) in different responses)
		
			@param peer The peer to add
		*/
		private void addPeer(Peer peer)
		{
			if (!peers.contains(peer))
			{
				peers.add(peer);
				
				//TODO: CONTACT NEW PEER???
				
				System.out.println("[PEER] " + peer.getPeerID() + " - " + peer.getIPAddr() + ":" + peer.getPort());
			}
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
	      		for(URL announceURL : info.getAnnounceUrls())
	      		{
				//Setting up the query URL
				String query = "?info_hash=" + encode(info.getInfoHash()) + "&peer_id=" + encode(peerID) + "&port=" + port + 
				"&uploaded=0&downloaded=0&left=" + info.getFileLength() + "&compact=0&no_peer_id=0&event=started";
			
				//Including the tracker ID if it was set by the tracker previously
				if(trackerID != null)
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
					InputStream connRead = conn.getInputStream();
					response = new byte[256];
					int totalBytesRead = 0;
					int bytesRead = 0;
				
					while((bytesRead = connRead.read(response, totalBytesRead, 256)) != -1)
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
				
					connRead.close();
		
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
			if(response == null)
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
				
				//Tracker ID is an optional field
				if(replyDictionary.containsKey("tracker id"))
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
						
						String peerID = peerDictionaryMap.get("peer id").getString();
						String IPAddr = peerDictionaryMap.get("ip").getString();
						int port = peerDictionaryMap.get("port").getInt();
						
						addPeer(new Peer(peerID, IPAddr, port));
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
						int port = ((((int)peers[c + 4]) << 8) + (int)peers[c + 5]) & 0xFFFF;
						
						addPeer(new Peer(IPAddr, port));
					}
				}
				
				//Scheduling another announce after the specified time interval
				System.out.println("Announce Successful! " + interval + " seconds until next announce");
				schedule(interval);
			}
			//Invalid response from the tracker (Could not be parsed)
			catch(Exception e)
			{
				System.out.println("ERROR: Received an invalid response from the tracker");
				System.out.println("Will retry in 30 seconds...");
				e.printStackTrace();
				schedule(30);
			}
		}
	}
}
