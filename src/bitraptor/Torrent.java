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
		
		//Creating a random peer ID
		peerID = new byte[20];
		(new Random()).nextBytes(peerID);
		peerID[0] = (byte)'B';
		peerID[1] = (byte)'R';
	}
	
	/**
		Announces the torrent information to the tracker and returns the tracker response.
		
		@return 		The response of the tracker or null on error.
		
		@throws IOException			Thrown if unable to contact or read from server
		@throws IndexOutOfBoundsException	Thrown if the hash or peerID are not of sufficient length
	*/
	public String announce() throws IOException, IndexOutOfBoundsException
	{
		//Checking for valid info hash and peer ID
		if (info.infoHash.length != 20)
		{
			throw new IndexOutOfBoundsException("Hash length is not 20 bytes");
		}
		
		//Initializing the connection
		URLConnection tracker = info.announceUrl.openConnection();
		tracker.setDoOutput(true);
		
		BufferedWriter trackerWriter = new BufferedWriter(new OutputStreamWriter(tracker.getOutputStream()));
		BufferedReader trackerReader = new BufferedReader(new InputStreamReader(tracker.getInputStream()));
		
		//Creating the request and sending it to the tracker
		//TODO: Set up event enum for started, stopped, completed. Or just a string. W.e
		trackerWriter.write(URLEncoder.encode("info_hash=" + (new String(info.infoHash)) + "&peer_id=" + (new String(peerID)) + "&port=" + port +
							 "&uploaded=0&downloaded=0&left=" + info.fileLength + "&compact=0no_peer_id=0" +
							 "&event=started","UTF-8"));
							 
		trackerWriter.close();
		trackerReader.close();
		
		return null;
	}
	
	public void start()
	{
		//Contacting the tracker for the first time
		try
		{
		/*
			Timer timer = new Timer(true);
			timer.scheduleTask(new TorrentAnnouncer(this), 0);
		*/
			announce(); //do you need this?  TorrentAnnouncer takes care of announcing Only because your shit is commented out atm kk fine
		}
		catch (IOException e)
		{
			System.out.println("ERROR: " + e);
			return;
		}
		catch (IndexOutOfBoundsException e)
		{
			System.out.println("ERROR: " + e);
			return;
		}
		
		//Do other shit
	}
	
	
	//Code for timer,
	/*
	private class TorrentAnnouncer extends TimerTask
	{
		private Torrent toAnnounce;
		public void run()
		{
			String response = toAnnounce().announce();
			BDecoder decoder = new BDecoder(new ByteArrayInputStream(response.getBytes()));
			
			try
			{
				Map<String, BEValue> replyDictionary = decoder.bdecode().getMap();
				
				if (replyDictionary.containsKey("failure reason"))
				{
					String reason = new String(replyDictionary.get("failure reason").getBytes());
					System.out.println("Announce Failed: " + reason);
					return;
				}
				int interval = replyDictionary.get("interval").getInt();
				String trackerID = new String(replyDictionary.get("tracker id").getBytes());
				int seeders = replyDictionary.get("complete").getInt();
				int leechers = replyDictionary.get("incomplete").getInt();
				List <BEValue> peers = replyDictionary.get("peers").getList();
				for (BEValue.)  
				new Timer(true).schedule(this, interval);
			}
			catch()
		}
		
		public TorrentAnnouncer(Torrent toAnnounce)
		{
			this.toAnnounce = toAnnounce;
		}
	}*/
}