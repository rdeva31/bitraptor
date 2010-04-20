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
	private HashMap<SocketChannel, Peer> peers;
	private BitSet requestedPieces;
	private State state = State.STARTED;
	
	/**
		Initializes the Torrent based on the information from the file.
		
		@param info Contains torrent characteristics
	*/
	public Torrent(Info info, int port)
	{
		this.port = port;
		this.info = info;
		
		peers = new HashMap<SocketChannel, Peer>();
	
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
		
		//Setting up bit set of requested pieces
		requestedPieces = new BitSet(info.getPieces().length / 20);
		
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
		Finds the next optimal piece to request relative to a given peer
		
		@returns The piece index or -1 if no good piece found
	*/
	private int getNextPiece(Peer peer)
	{
		BitSet pieces = (BitSet)(peer.getPieces().clone());
		pieces.andNot(requestedPieces);
		int[] pieceCounts = new int[pieces.length()];
		
		//No piece found if all the pieces the peer has are requested (or if peer had no pieces)
		if(pieces.isEmpty())
		{
			return -1;
		}
		
		//Initialize piece counts based on what the peer has
		for (int c = 0; c < pieceCounts.length; c++)
		{
			if (pieces.get(c))
			{
				pieceCounts[c] = 1;
			}
			else
			{
				pieceCounts[c] = 0;
			}
		}
		
		//Adding to piece counts based on shared pieces
		Collection<Peer> peerSet = peers.values();
		for (Peer p : peerSet)
		{
			if (p.equals(peer))
			{
				continue;
			}
			
			BitSet shared = (BitSet)(p.getPieces().clone());
			shared.and(pieces);
			
			int c = -1;
			while ((c = shared.nextSetBit(c+1)) != -1)
			{
				pieceCounts[c] += 1;
			}
		}
		
		//Finding the smallest count, which is rarest piece
		int piece = requestedPieces.nextClearBit(0);
		int smallest = pieceCounts[requestedPieces.nextClearBit(0)];
		
		for (int c = 0; c < pieceCounts.length; c++)
		{
			if (pieceCounts[c] > 0 && pieceCounts[c] < smallest)
			{
				piece = c;
			}
		}
		
		return piece;
	}
	
	/**
		Starts downloading the torrent and handles the main interactions
	*/
	public void start()
	{
		final int BLOCK_SIZE = 16*1024;
				
		//Starting up the announcer (runs immediately and then schedules a next run)
		(new TorrentAnnouncer(this)).run();
		
		//Looping forever
		while(true)
		{
			//Setting up new piece requests for peers if needed
			Collection<Peer> peerSet = peers.values();
			for (Peer peer : peerSet)
			{
				if(peer.isHandlingRequest())
				{
					continue;
				}
				
				//Finding the optimal piece to request from the peer
				int piece = getNextPiece(peer);
				
				//No piece found that we want to request from peer
				if (piece == -1)
				{
					peer.setInterested(false);
					continue;
				}
				
				
				List<Request> requests = new ArrayList<Request>();
				for (int c = 0; c < info.getPieceLength(); c += BLOCK_SIZE) 
				{
					requests.add(new Request(piece, c, BLOCK_SIZE)); 
				}
				
				System.out.println("[ADD REQUEST] Piece # " + piece + " from peer " + peer.getSockAddr());
				
				requestedPieces.set(piece);
				
				peer.setInterested(true);
				peer.addRequests(requests);
			}
			
			/*
			//Choke all but the top 4 peers
			List<Peer> valuablePeers = new ArrayList<Peer>(peers.values());
			Collections.sort(valuablePeers, 
				new Comparator<Peer>()
				{
					public int compare(Peer a, Peer b)
					{
						return new Integer(a.getPeerValue()).compareTo(b.getPeerValue());
					}
				});
				
			int counter = 0;
			for (Peer p : valuablePeers)
			{
				if (counter++ < 4)
				{
					p.setChoking(false);
				}
				else
				{
					p.setChoking(true);
				}
			}
			*/
			
			//Handshake Selector
			try
			{
				//Performing the select
				handshakeSelect.selectNow();
				Iterator it = handshakeSelect.selectedKeys().iterator();
				
				while(it.hasNext())
				{
					SelectionKey selected = (SelectionKey)it.next();
					it.remove();
				
					//Getting the peer associated with the socket channel
					SocketChannel sock = (SocketChannel)selected.channel();
					Peer peer = peers.get(sock);
				
					//Handling the read if possible
					if (selected.isReadable())
					{
						if((sock.read(peer.getReadBuffer()) > 0) && (peer.getReadBuffer().position() >= 68))
						{
							System.out.print("[HANDSHAKE] " + peer.getSockAddr());

							//Moving the peer to the main selector if the handshake checks out
							if(peer.checkHandshake() == true)
							{
								System.out.println("... Success!");
								selected.cancel();
								sock.register(select, SelectionKey.OP_WRITE | SelectionKey.OP_READ);
							}
							//Removing the peer if the handshake does not check out
							else
							{
								System.out.println("... FAILED!");
								selected.cancel();
								peers.remove(sock);
							}

							continue;
						}
					}
				
					//Handling the write if possible
					if (selected.isWritable())
					{
						peer.getWriteBuffer().flip();
						if(peer.getWriteBuffer().hasRemaining())
						{
							if(sock.write(peer.getWriteBuffer()) > 0)
							{
								System.out.println("[HANDSHAKE SENT] " + peer.getSockAddr());
							}
						}
						peer.getWriteBuffer().compact();
					}
				}
			}
			catch (Exception e)
			{
				System.out.println("EXCEPTION: " + e);
				e.printStackTrace();
				return;
				//TODO: Watch out for ClosedChannelException? to remove the peer
			}
		
			//Main Selector
			try
			{
				//Performing the select
				select.selectNow();
				Iterator it = select.selectedKeys().iterator();
				
				while(it.hasNext())
				{
					SelectionKey selected = (SelectionKey)it.next();
					it.remove();
					
					//Getting the peer associated with the socket channel
					SocketChannel sock = (SocketChannel)selected.channel();
					Peer peer = peers.get(sock);
				
					//Handling the read if possible
					if (selected.isReadable())
					{
						if(sock.read(peer.getReadBuffer()) > 0)
						{
							System.out.println("[READ] " + peer.getSockAddr());
							peer.handleMessages();
						}
					}
				
					//Handling the write if possible
					if (selected.isWritable())
					{
						peer.setupWrites();
						
						peer.getWriteBuffer().flip();
						
						if(peer.getWriteBuffer().hasRemaining())
						{
							if(sock.write(peer.getWriteBuffer()) > 0)
							{
								System.out.println("[WRITE] " + peer.getSockAddr());
							}
						}
						
						peer.getWriteBuffer().compact();
					}
				}
			}
			catch (Exception e)
			{
				System.out.println("EXCEPTION: " + e);
				e.printStackTrace();
				return;
				//TODO: Watch out for ClosedChannelException? to remove the peer
			}
		}
	}

	public Info getInfo()
	{
		return info;
	}
		
	/**
		Attempts to connect to the peer, and if not an incoming peer, perform a handshake with it before 
		adding it to the list of peers
	
		@param peer The peer to add
	*/
	public void addPeer(Peer peer, boolean incoming)
	{
		//Making sure that the peer is not already in the list
		if (!peers.containsValue(peer))
		{
			try
			{
				//Connect to the peer via TCP
				peer.connect();
				
				//Sending a handshake message to the peer
				peer.getWriteBuffer().put((byte)19).put(protocolName).putDouble(0.0).put(info.getInfoHash()).put(peerID);
				
				//Incoming Peer: Already received a valid handshake, so place in main selector
				if (incoming)
				{
					System.out.println("[PEER INC] " + peer.getSockAddr());
					peer.getSocket().register(select, SelectionKey.OP_WRITE | SelectionKey.OP_READ);
				}
				//Outgoing Peer: Waiting on valid handshake, so place in handshake selector
				else
				{
					System.out.println("[PEER OUT] " + peer.getSockAddr());
					peer.getSocket().register(handshakeSelect, SelectionKey.OP_WRITE | SelectionKey.OP_READ);
				}
			}
			catch (IOException e)
			{
				System.out.println("EXCEPTION: " + e);
				return;
			}
			
			//Adding the peer to the list
			peers.put(peer.getSocket(), peer);
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

					//Disconnecting from the tracker
					istream.close();
					conn.disconnect();

					break;
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
						
						addPeer(new Peer(info, peerID, IPAddr, port), false);
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
						
						addPeer(new Peer(info, new byte[20], IPAddr, port), false);
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
