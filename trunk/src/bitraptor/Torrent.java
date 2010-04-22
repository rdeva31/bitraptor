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
	private HashMap<Integer, Piece> pieces;
	private HashMap<Peer, Boolean> uploadSlotActions;
	private BitSet receivedPieces;
	private BitSet requestedPieces;
	private LinkedList<Request> requestPool;
	private State state = State.STARTED;
	private boolean inEndGameMode;
	
	private final int SLOT_ASSIGN_TIMER_PERIOD = 10*1000; //in milliseconds
	private final int NUM_UPLOAD_SLOTS = 5; //number of upload slots
	private final int BLOCK_SIZE = 16*1024;
	
	private final int REQUEST_TIMER_PERIOD = 1000; //in milliseconds
	private boolean requestTimerStatus = false; 
	
	private final int END_GAME_REQUEST_TIMER_PERIOD = 15*1000; //in milliseconds
	private boolean endGameRequestTimerStatus = false; 
	
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
		pieces = new HashMap<Integer, Piece>();
		uploadSlotActions = new HashMap<Peer, Boolean>();
		requestedPieces = new BitSet(info.getPieces().length / 20);
		receivedPieces = new BitSet(info.getPieces().length / 20);
		requestPool = new LinkedList<Request>();
		
		inEndGameMode = false;
		
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
	
	public BitSet getRequestedPieces()
	{
		return requestedPieces;
	}
	
	public BitSet getReceivedPieces()
	{
		return receivedPieces;
	}
	
	public boolean isInEndGameMode()
	{
		return inEndGameMode;
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
		
		//Adding to piece counts based on shared piecess
		Collection<Peer> peerSet = peers.values();
		for (Peer p : peerSet)
		{
			//Skipping over the peer itself since it was already taken into account
			if (p.equals(peer))
			{
				continue;
			}
			
			//Finding the pieces shared between the peers
			BitSet sharedPieces = p.getPieces();
			sharedPieces.and(pieces);
			
			//Incrementing the shared pieces in the counts array
			int curPiece = -1;
			while ((curPiece = sharedPieces.nextSetBit(curPiece + 1)) != -1)
			{
				pieceCounts[curPiece] += 1;
			}
		}
		
		//Finding the smallest count (greater than 0) and all of the pieces that have that count value
		int lowestCount = Integer.MAX_VALUE;
		LinkedList<Integer> bestPieces = new LinkedList<Integer>();
		int curPieceIndex = -1;
		
		while ((curPieceIndex = pieces.nextSetBit(curPieceIndex + 1)) != -1)
		{
			if ((pieceCounts[curPieceIndex] > 0) && (pieceCounts[curPieceIndex] < lowestCount))
			{
				lowestCount = pieceCounts[curPieceIndex];
				bestPieces.clear();
				bestPieces.add(curPieceIndex);
			}
			else if (pieceCounts[curPieceIndex] == lowestCount)
			{
				bestPieces.add(curPieceIndex);
			}
		}
		
		//Choosing a random piece out of the ones that share the lowest count
		int pieceIndex = bestPieces.get((int)(Math.random() * (bestPieces.size() - 1)));
		
		//Finding all peers that have that piece and are not choking us
		for (Peer pp : peerSet)
		{
			if ((pp.getPieces().get(pieceIndex)) && (!pp.isPeerChoking()))
			{
				peerList.add(pp);
			}
		}
		
		return pieceIndex;
	}
	
	public void finishRequest(Request request)
	{
		//Only interested if the torrent is in end game mode
		if (!inEndGameMode)
		{
			return;
		}
		
		//Going through all the peers and removing the request
		Collection<Peer> peerSet = peers.values();
		for (Peer peer : peerSet)
		{
			peer.removeRequest(request);
		}
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
				pieces.remove(piece);
				
				System.out.println("[DOWNLOADED] " + pieceIndex);
				
				//Sending out HAVE messages to all peers for the new piece
				ByteBuffer payload = ByteBuffer.allocate(4);
				payload.order(ByteOrder.BIG_ENDIAN);
				payload.putInt(pieceIndex);
					
				Collection<Peer> peerSet = peers.values();
				for (Peer peer : peerSet)
				{
					peer.writeMessage(Peer.MessageType.HAVE, payload);
				}
			}
			catch (Exception e)
			{
				System.out.println("[FAIL] " + e);
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
	
	public void addRequestsToPool(LinkedList<Request> requests)
	{
		requestPool.addAll(requests);
	}
	
	public void generateRequestsEndGame(Piece piece, Peer peer)
	{		 
		//Generating all of the block requests for the piece
		LinkedList<Request> requests = new LinkedList<Request>();
		for (int c = 0; c < piece.getPieceLength(); c += BLOCK_SIZE) 
		{
			//Calculating the block size (possibly shorter if it is the last block in the last piece)
			int blockSize = BLOCK_SIZE;
			if (c + BLOCK_SIZE > piece.getPieceLength())
			{
				blockSize = piece.getPieceLength() - c;
			}
			
			requests.add(new Request(piece, piece.getPieceIndex(), c, blockSize)); 
		}
			
//		System.out.println("[END GAME REQUEST] Piece #" + piece.getPieceIndex() + " from peer " + peer.getSockAddr());
		
		//Adding all of the requests to the peer
		int totalRequests = requests.size();
		for (int r = 0; r < totalRequests; r++)
		{
			peer.addRequest(requests.remove());
		}
	}
	
	public void generateRequests(int p, LinkedList<Peer> peerList)
	{
		//Calculating the piece length (possibly shorter if it is the last piece)
		int pieceLength = info.getPieceLength();
		if (p == ((info.getPieces().length / 20) - 1))
		{
			pieceLength = info.getFileLength() - (p * pieceLength);
		}
		
		//Initializing a piece and adding it to the map of pieces
		Piece piece = new Piece(p, pieceLength);
		pieces.put(p, piece);
		 
		//Generating all of the block requests for the piece
		LinkedList<Request> requests = new LinkedList<Request>();
		for (int c = 0; c < pieceLength; c += BLOCK_SIZE) 
		{
			//Calculating the block size (possibly shorter if it is the last block in the last piece)
			int blockSize = BLOCK_SIZE;
			if (c + BLOCK_SIZE > pieceLength)
			{
				blockSize = pieceLength - c;
			}
			
			requests.add(new Request(piece, p, c, blockSize)); 
		}
			
//		System.out.println("[ADD REQUEST] Piece #" + p + " from " + peerList.size() + " peers");
		
		//Dividing up requests among all of the peers 
		//TODO: Divide based on how many requests they currently have pending
		int totalRequests = requests.size();
		int totalPeers = peerList.size();
		int requestsPerPeer = (int)Math.ceil((double)totalRequests / (double)peerList.size());
		
		//Less requests than peers available, so adding requests to peers with lowest request counts
		if(totalRequests < totalPeers)
		{
			Collections.sort(peerList);
			
			for (int r = 0; r < totalRequests; r++)
			{
				Request curRequest = requests.remove();
				peerList.get(r).addRequest(curRequest);
		
//				System.out.println("\tBlock Offset " + curRequest.getBlockOffset() + " - " + peerList.get(r / requestsPerPeer).getSockAddr());
			}
		}
		//More requests than peers available, do dividing up requests between all the peers
		else
		{
			for (int r = 0; r < totalRequests; r++)
			{
				Request curRequest = requests.remove();
				peerList.get(r / requestsPerPeer).addRequest(curRequest);
		
//				System.out.println("\tBlock Offset " + curRequest.getBlockOffset() + " - " + peerList.get(r / requestsPerPeer).getSockAddr());
			}
		}
	}
	
	/**
		Starts downloading the torrent and handles the main interactions
	*/
	public void start()
	{
				
		//Starting up the announcer (runs immediately and then schedules a next run)
		(new TorrentAnnouncer(this)).run();
		
		//Starting up the upload slot assign timer
		Timer uploadSlotTimer = new Timer();
		uploadSlotTimer.scheduleAtFixedRate(new UploadSlotAssigner(NUM_UPLOAD_SLOTS), 0, SLOT_ASSIGN_TIMER_PERIOD);
		
		//Starting up the request timer
		Timer requestTimer = new Timer();
		requestTimer.scheduleAtFixedRate(new RequestTimer(), 0, REQUEST_TIMER_PERIOD);
		
		//Looping forever
		while(true)
		{
			//Handling any upload slot actions that were generated
			if (uploadSlotActions.size() > 0) //please do because I have ZERO clue
			{
				synchronized(uploadSlotActions)
				{
					try
					{
						Set<Peer> peerSet = uploadSlotActions.keySet(); 
						for (Peer peer : peerSet)
						{
							peer.setChoking(uploadSlotActions.get(peer));
						}
				
						uploadSlotActions.clear();
					}
					catch (Exception e)
					{
					}
				}
			}
		
			//Downloaded all of the pieces
			if (receivedPieces.cardinality() == (info.getPieces().length / 20))
			{
				System.out.println("FINISHED!");
				uploadSlotTimer.cancel();
				state = State.COMPLETED;
				break;
			}
			
			//Checking to see if the torrent can start end game mode
			if (((requestedPieces.cardinality() >= (info.getPieces().length / 20)) && (!inEndGameMode))
				|| (inEndGameMode && endGameRequestTimerStatus))
			{
				//Previously not in end game mode
				if (!inEndGameMode)
				{
					System.out.println("***ENTERING END GAME MODE***");
				
					inEndGameMode = true;
		
					//Starting up the end game mode request timer
					Timer endGameRequestTimer = new Timer();
					endGameRequestTimer.scheduleAtFixedRate(new EndGameRequestTimer(), END_GAME_REQUEST_TIMER_PERIOD, END_GAME_REQUEST_TIMER_PERIOD);
				}
				else
				{
					endGameRequestTimerStatus = false;
					System.out.println("[END GAME PROCESSING]");
				}
				
				//Getting the set of peers
				Collection<Peer> peerSet = peers.values();
				
				//Calculating the unfulfilled requests
				BitSet unfulfilledPieces = ((BitSet)receivedPieces.clone());
				unfulfilledPieces.flip(0, (info.getPieces().length / 20));
				
				//Going through all of the unfulfilled pieces and adding them to a list
				int pieceIndex = -1;
				LinkedList<Piece> pieceList = new LinkedList<Piece>();
				while ((pieceIndex = unfulfilledPieces.nextSetBit(pieceIndex + 1)) != -1)
				{
					//Finding the corresponding piece to the index
					Piece piece = pieces.get(pieceIndex);
					
					if (piece == null)
					{
						continue;
					}
					
					//Sending out the requests to all of the peers that have the piece (make sure they know you are interested too)
					for (Peer peer : peerSet)
					{
						if(peer.getPieces().get(pieceIndex))
						{
							peer.setInterested(true);
							generateRequestsEndGame(piece, peer);
						}
					}
				}
					
				//Forcing each peer to shuffle the order of their requests in order to increase speed
				for (Peer peer : peerSet)
				{
					peer.shuffleRequests();
				}
			}
		
			//Add requests from the request pool to random peers
			LinkedList<Request> tempPool = new LinkedList<Request>(requestPool);
			for (Request request : tempPool)
			{
				int pieceIndex = request.getPieceIndex();
			
				//Going through all the peers
				Collection<Peer> peerSet = peers.values();
				LinkedList<Peer> peersWithPiece = new LinkedList<Peer>();
				for (Peer peer : peerSet)
				{
					//Peer has the piece
					if (peer.getPieces().get(pieceIndex))
					{
						peersWithPiece.add(peer);
					}
				}
				
				//Letting the request sit in the pool since no peer can handle it
				if (peersWithPiece.size() == 0)
				{
					continue;
				}
				
				//In end game mode, so send it to all possible peers
				if (inEndGameMode)
				{
					for (Peer peer : peersWithPiece)
					{
						peer.setInterested(true);
						peer.addRequest(request);
					}
				}
				//Not in end game mode, so choose a random peer
				else
				{
					//Choosing the random peer to add the request to
					Peer randomPeer = peersWithPiece.get((int)(Math.random() * (peersWithPiece.size() - 1)));
					randomPeer.setInterested(true);
					randomPeer.addRequest(request);
				}
				
				//Removing the request from the pool
				requestPool.remove(request);
			}
		
			//Generate new piece requests as needed
			if (requestTimerStatus)
			{
				requestTimerStatus = false;
			
				Collection<Peer> peerSet = peers.values();
				for (Peer peer : peerSet)
				{
					//Skipping over any peers with requests already or peers that are choking us
					if(peer.isHandlingRequest() || peer.isPeerChoking())
					{
						continue;
					}
				
					//Finding the optimal piece to request, and the list of peers that have it
					LinkedList<Peer> peerList = new LinkedList<Peer>();
					int p = getNextPiece(peer, peerList);
				
					//No piece found that we want to request from peer (also notify that we are no longer interested)
					if (p == -1)
					{
						peer.setInterested(false);
						continue;
					}
				
					//Generating all of the requests and adding them to the peers
					generateRequests(p, peerList);
				
					//Set that the piece was requested
					requestedPieces.set(p);
				}
			}
			
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
				
					try
					{
    					//Handling the connect if possible
						if (selected.isConnectable())
						{
							if (sock.finishConnect())
							{
//								System.out.println("[CONNECT] " + peer.getSockAddr());
								selected.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
							}
							else
							{
								selected.cancel();
								throw new Exception("Unable to connect to the peer");
							}
						}
						
						//Handling the read if possible
						if (selected.isReadable())
						{
							if((sock.read(peer.getReadBuffer()) > 0) && (peer.getReadBuffer().position() >= 68))
							{
//								System.out.print("[HANDSHAKE] " + peer.getSockAddr());

								//Moving the peer to the main selector if the handshake checks out
								if(peer.checkHandshake() == true)
								{
//									System.out.println("... Success!");
									selected.cancel();
									sock.register(select, SelectionKey.OP_CONNECT | SelectionKey.OP_WRITE | SelectionKey.OP_READ);
								
									//Expressing interest in the peer automatically
									peer.writeMessage(Peer.MessageType.INTERESTED, null);
								}
								//Removing the peer if the handshake does not check out
								else
								{
//									System.out.println("... FAILED!");
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
//									System.out.println("[HANDSHAKE SENT] " + peer.getSockAddr());
								}
							}
							peer.getWriteBuffer().compact();
						}
					}
					//Removing the peer due to exception
					catch (Exception e)
					{
//						System.out.println("Force Remove: " + e);
//						e.printStackTrace();
						selected.cancel();
						forceRemovePeer(peer);
					}
				}
			}
			//Removing the peer due to exception
			catch (Exception e)
			{
//				System.out.println("Exception: " + e);
//				e.printStackTrace();
				return;
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
					
					try
					{
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
					//Removing the peer due to exception
					catch (Exception e)
					{
//						System.out.println("Force Remove: " + e);
//						e.printStackTrace();
						selected.cancel();
						forceRemovePeer(peer);
					}
				}
			}
			//Removing the peer due to exception
			catch (Exception e)
			{
//				System.out.println("Exception: " + e);
				e.printStackTrace();
				return;
			}
		}
	}
	
	public void forceRemovePeer(Peer peer)
	{
		//Adding all the requests to the torrent request pool (if not in end game mode)
		if (!inEndGameMode)
		{
			addRequestsToPool(peer.getRequests());
			addRequestsToPool(peer.getSentRequests());
		}
		
		//Removing the peer from the map
		peers.remove(peer.getSocket());
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
				
				//Sending the handshake message to the peer
				peer.getWriteBuffer().put((byte)19).put(protocolName).putDouble(0.0).put(info.getInfoHash()).put(peerID);
				
				//Incoming Peer: Already received a valid handshake, so place in main selector
				if (incoming)
				{
//					System.out.println("[PEER INC] " + peer.getSockAddr());
					peer.getSocket().register(select, SelectionKey.OP_WRITE | SelectionKey.OP_READ);
				}
				//Outgoing Peer: Waiting on connection and a valid handshake, so place in handshake selector
				else
				{
//					System.out.println("[PEER OUT] " + peer.getSockAddr());
					peer.getSocket().register(handshakeSelect, SelectionKey.OP_CONNECT);
				}
			}
			catch (IOException e)
			{
//				System.out.println("EXCEPTION: " + e);
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
//					System.out.println("[ANNOUNCE] " + announceURL.toString());
					
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
//				System.out.println("ERROR: Couldn't announce");
//				System.out.println("Will retry in 30 seconds...");
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
//					System.out.println("Announce Failed: " + reason);
					
//					System.out.println("Will retry in 30 seconds...");
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
//				System.out.println("Announce Successful! " + interval + " seconds until next announce");
				schedule(interval);
			}
			//Invalid response from the tracker (Could not be parsed)
			catch (Exception e)
			{
//				System.out.println("ERROR: Received an invalid response from the tracker");
//				System.out.println("Will retry in 30 seconds...");
				e.printStackTrace();
				schedule(30);
			}
		}
	}
	
	private class UploadSlotAssigner extends TimerTask
	{
		private int slots;
		public UploadSlotAssigner(int slots)
		{
			this.slots = slots;
		}
		public void run()
		{
			LinkedList<Peer> peerList = new LinkedList<Peer>(peers.values());
			Collections.sort(peerList, new Comparator<Peer>()
			{
				public int compare(Peer a, Peer b)
				{
					int deltaA = a.getDownloaded() - a.getUploaded();
					int deltaB = b.getDownloaded() - b.getUploaded();
					
					a.resetUploaded();
					b.resetUploaded();
					a.resetDownloaded();
					b.resetDownloaded();
					
					return new Integer(deltaB).compareTo(deltaA);
				}
			});
			
			synchronized(uploadSlotActions)
			{
				//choke all seeders
				for (Peer p : peerList)
				{
					if (p.getPieces().nextClearBit(0) == -1) 
						uploadSlotActions.put(peerList.remove(), true);
				}
				//Unchoke top slots-1 peers
				int numUnchoke = Math.min(slots - 1, peerList.size());
				for (int c = 0; c < numUnchoke; c++)
				{
					uploadSlotActions.put(peerList.remove(), false);
				}
				
				//Unchoke random peer
				if (peerList.size() > 0)
				{
					Collections.shuffle(peerList);
					uploadSlotActions.put(peerList.remove(), false);
				}
			
				//Choke the rest
				if (peerList.size() > 0)
				{
					for(Peer peer : peerList)
					{
						uploadSlotActions.put(peer, true);
					}
				}
			}
		}
	}
	
	private class RequestTimer extends TimerTask
	{
		public void run()
		{
			requestTimerStatus = true;
		}
	} 
	
	private class EndGameRequestTimer extends TimerTask
	{
		public void run()
		{
			endGameRequestTimerStatus = true;
		}
	} 
}
