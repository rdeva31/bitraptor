package bitraptor;

import java.lang.*;
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
	private BitSet receivedPieces;
	private BitSet requestedPieces;
	private LinkedList<Request> requestPool;
	private State state = State.STARTED;
	
	/**
		Initializes the Torrent based on the information from the file.
		
		@param info Contains torrent characteristics
	*/
	public Torrent(Info info, int port)
	{
		this.port = port;
		this.info = info;
	
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
		
		peers = new HashMap<SocketChannel, Peer>();
		requestedPieces = new BitSet(info.getPieces().length / 20);
		receivedPieces = new BitSet(info.getPieces().length / 20);
		requestPool = new LinkedList<Request>();
		
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

	public Info getInfo()
	{
		return info;
	}
	
	public Collection<Peer> getPeers()
	{
		return peers.values();
	}
	
	public BitSet getReceivedPieces()
	{
		return receivedPieces;
	}
	
	/**
		Finds the next optimal piece to request relative to a given peer
		
		@returns The piece index or -1 if no good piece found
	*/
	private int getNextPiece(Peer peer, LinkedList<Peer> peerList)
	{
		BitSet pieces = ((BitSet)peer.getPieces().clone());
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
			//Skipping over the peer itself since it was already taken into account
			if (p.equals(peer))
			{
				continue;
			}
			
			BitSet sharedPieces = p.getPieces();
			sharedPieces.and(pieces);
			
			int curPiece = -1;
			while ((curPiece = sharedPieces.nextSetBit(curPiece + 1)) != -1)
			{
				pieceCounts[curPiece] += 1;
			}
		}
		
		//Finding the smallest count (greater than 0) and the pieces that have that count
		int lowestCount = Integer.MAX_VALUE;
		LinkedList<Integer> bestPieces = new LinkedList<Integer>();
		int curPiece = -1;
		
		while ((curPiece = pieces.nextSetBit(curPiece + 1)) != -1)
		{
			if ((pieceCounts[curPiece] > 0) && (pieceCounts[curPiece] < lowestCount))
			{
				lowestCount = pieceCounts[curPiece];
				bestPieces.clear();
				bestPieces.add(curPiece);
			}
		}
		
		//Choosing a random piece out of the ones that share the lowest count
		int piece = bestPieces.get((int)(Math.random() * (bestPieces.size() - 1)));
		
		//Finding all peers that have that piece
		for (Peer pp : peerSet)
		{
			if (pp.getPieces().get(piece))
			{
				peerList.add(pp);
			}
		}
		
		return piece;
	}
	
	public void finishPiece(Piece piece)
	{
		int pieceIndex = piece.getPieceIndex();
		byte[] downloadedHash = null;
		
		try
		{
			downloadedHash = piece.hash();
		}
		catch (Exception e)
		{
			requestedPieces.clear(pieceIndex);
		}
		
		//The hash of the downloaded piece matches the known hash
		if(Arrays.equals(downloadedHash, Arrays.copyOfRange(info.getPieces(), pieceIndex * 20, (pieceIndex + 1) * 20)))
		{
			try
			{
				info.writePiece(piece.getBytes(), pieceIndex);
				receivedPieces.set(pieceIndex);
				
				System.out.println("[DOWNLOADED] " + pieceIndex);
				
				//Sending out HAVE messages to all peers for the new piece
				Collection<Peer> peerSet = peers.values();
				for (Peer peer : peerSet)
				{
					ByteBuffer payload = ByteBuffer.allocate(4);
					payload.order(ByteOrder.BIG_ENDIAN);
					payload.putInt(pieceIndex);
					peer.writeMessage(Peer.MessageType.HAVE, payload);
				}
			}
			catch (Exception e)
			{
				System.out.println("[FAIL] " + pieceIndex);
				requestedPieces.clear(pieceIndex);
			}
		}
		//The hashes do not match
		else
		{
			System.out.println("[HASH FAIL] " + pieceIndex);
			requestedPieces.clear(pieceIndex);
		}
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
			//Handling various functions for each peer
			Collection<Peer> peerSet = peers.values();
			for (Peer peer : peerSet)
			{
				//Skipping over any peers that already are handling requests or choked
				if(peer.isHandlingRequest() || peer.isPeerChoking())
				{
					continue;
				}
				
				//Finding the optimal piece to request, and the list of peers that have it
				LinkedList<Peer> peerList = new LinkedList<Peer>();
				int p = getNextPiece(peer, peerList);
				
				//No piece found that we want to request from peer
				if (p == -1)
				{
					continue;
				}
				
				//Initializing a piece
				Piece piece = new Piece(p, info.getPieceLength());
				
				//Generating all of the block requests for the piece
				LinkedList<Request> requests = new LinkedList<Request>();
				for (int c = 0; c < info.getPieceLength(); c += BLOCK_SIZE) 
				{
					requests.add(new Request(piece, p, c, BLOCK_SIZE)); 
				}
					
				System.out.println("[ADD REQUEST] Piece #" + p + " from " + peerList.size() + " peers");
				
				//Dividing up requests among all of the peers 
				//TODO: Divide based on how many requests they currently have pending
				int totalRequests = requests.size();
				int requestsPerPeer = (int)Math.ceil((double)totalRequests / (double)peerList.size());
				for (int r = 0; r < totalRequests; r++)
				{
					Request curRequest = requests.remove();
					peerList.get(r / requestsPerPeer).addRequest(curRequest);
				
					System.out.println("\tBlock Offset " + curRequest.getBlockOffset() + " - " + peerList.get(r / requestsPerPeer).getSockAddr());
				}
				
				//Set that the piece was requested
				requestedPieces.set(p);
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
								sock.register(select, SelectionKey.OP_CONNECT | SelectionKey.OP_WRITE | SelectionKey.OP_READ);
								
								//Expressing interest in the peer automatically
								peer.writeMessage(Peer.MessageType.INTERESTED, null);
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
						peer.setupWrites();
				
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
				
				//Incoming Peer: Already received a valid handshake, so place in main selector
				if (incoming)
				{
					System.out.println("[PEER INC] " + peer.getSockAddr());
					peer.getSocket().register(select, SelectionKey.OP_WRITE | SelectionKey.OP_READ);
					
					//Sending handshake message to the peer
					peer.getWriteBuffer().put((byte)19).put(protocolName).putDouble(0.0).put(info.getInfoHash()).put(peerID);
					
					//Expressing interest in the peer automatically
					peer.writeMessage(Peer.MessageType.INTERESTED, null);
				}
				//Outgoing Peer: Waiting on valid handshake, so place in handshake selector
				else
				{
					System.out.println("[PEER OUT] " + peer.getSockAddr());
					peer.getSocket().register(handshakeSelect, SelectionKey.OP_WRITE | SelectionKey.OP_READ);
					
					//Sending handshake message to the peer
					peer.getWriteBuffer().put((byte)19).put(protocolName).putDouble(0.0).put(info.getInfoHash()).put(peerID);
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
						
						addPeer(new Peer(toAnnounce, peerID, IPAddr, port), false);
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
						
						addPeer(new Peer(toAnnounce, new byte[20], IPAddr, port), false);
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
