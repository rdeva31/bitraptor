package bitraptor;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;

public class Peer
{

	public enum MessageType {
		CHOKE(0), 
		UNCHOKE(1), 
		INTERESTED(2), 
		UNINTERESTED(3), 
		HAVE(4), 
		BITFIELD(5), 
		REQUEST(6), 
		PIECE(7), 
		CANCEL(8);
		
		private int value;
		MessageType(int value)
		{
			this.value = value;
		}
		
		public int valueOf()
		{
			return value;
		}
		
		public static MessageType fromInt(int val) throws Exception
		{
			for (MessageType m : MessageType.values())
			{
				if (val == m.value)
				{
					return m;
				}
			}
			
			throw new Exception("Unrecognized enum value: " + val);
		}
	};
	
	private static byte[] protocolName = {'B', 'i', 't', 'T', 'o', 'r', 'r', 'e', 'n', 't', ' ', 'p', 'r', 'o', 't', 'o', 'c', 'o', 'l'};
	
	private Info info;
	private byte[] peerID;
	private SocketChannel sock;
	private InetSocketAddress sockAddr;
	private boolean meChoking, meInterested;
	private boolean peerChoking, peerInterested;
	private BitSet pieces;
	private ByteBuffer readBuffer, writeBuffer, writeMsgBuffer;
	
	//Variables for handling requests
	private LinkedList<Request> peerRequests;
	private Request curPeerRequest;
	private ByteBuffer peerBlockBuffer;
	boolean isSendingBlock;
	
	private LinkedList<Request> meRequests;
	private Request curMeRequest;
	private ByteBuffer mePieceBuffer;
	private ByteBuffer meBlockBuffer;
	boolean isReceivingBlock;
	
	private int peerValue;  
	
	//Constructor for incoming peers
	public Peer(Info info, byte[] peerID, SocketChannel sock)
	{
		this(info, peerID, sock, (InetSocketAddress)(sock.socket().getRemoteSocketAddress()));
	}
	
	//Constructor for outgoing peers
	public Peer(Info info, byte[] peerID, String IPAddr, int port)
	{
		this(info, peerID, (SocketChannel)null, new InetSocketAddress(IPAddr, port));
	}
	
	public Peer(Info info, byte[] peerID, SocketChannel sock, InetSocketAddress sockAddr)
	{
		this.info = info;
		this.peerID = peerID;
		this.sock = sock;
		this.sockAddr = sockAddr;
		meChoking = true;
		meInterested = false;
		peerChoking = true;
		peerInterested = false;
		pieces = new BitSet(info.getPieces().length / 20);
		readBuffer = ByteBuffer.allocateDirect(4096);
		writeBuffer = ByteBuffer.allocateDirect(4096);
		writeMsgBuffer = ByteBuffer.allocateDirect(4096);
		readBuffer.order(ByteOrder.BIG_ENDIAN);
		writeBuffer.order(ByteOrder.BIG_ENDIAN);
		writeMsgBuffer.order(ByteOrder.BIG_ENDIAN);
		peerRequests = new LinkedList<Request>();
		curPeerRequest = null;
		peerBlockBuffer = null;
		isSendingBlock = false;
		meRequests = new LinkedList<Request>();
		curMeRequest = null;
		meBlockBuffer = null;
		mePieceBuffer = null;
		isReceivingBlock = false;
		
		peerValue = (info.getPieces().length / 20) < 50 ?  
				((info.getPieces().length / 20) / 4) : 50 ;
	}
	
	public void connect() throws IOException
	{
		//Setting up the socket connection if necessary
		if (sock == null)
		{
			sock = SocketChannel.open();
			sock.configureBlocking(false);
			sock.socket().setReuseAddress(true);
			sock.connect(sockAddr);
			while(!sock.finishConnect())
			{
			}
		}
	}
	
	public byte[] getPeerID()
	{
		return peerID;
	}
	
	public SocketChannel getSocket()
	{
		return sock;
	}
	
	public InetSocketAddress getSockAddr()
	{
		return sockAddr;
	}
	
	public void setChoking(boolean choking)
	{
		if (choking && !meChoking)
		{
			writeMessage(MessageType.CHOKE, null);
		}
		else if (!choking && meChoking)
		{
			writeMessage(MessageType.UNCHOKE, null);
		}
		
		meChoking = choking;
	}
	
	public void setInterested(boolean interested)
	{
		if (interested && !meInterested)
		{
			writeMessage(MessageType.INTERESTED, null);
		}
		else if (!interested && meInterested)
		{
			writeMessage(MessageType.UNINTERESTED, null);
		}
		
		meInterested = interested;
	}
	
	public boolean isChoking()
	{
		return meChoking;
	}
	
	public boolean isInterested()
	{
		return meInterested;
	}
	
	public void setPeerChoking(boolean choking)
	{
		peerChoking = choking;
	}
	
	public void setPeerInterested(boolean interested)
	{
		peerInterested = interested;
	}
	
	public boolean isPeerChoking()
	{
		return peerChoking;
	}
	
	public boolean isPeerInterested()
	{
		return peerInterested;
	}
	
	public BitSet getPieces()
	{
		return pieces;
	}
	
	public ByteBuffer getReadBuffer()
	{
		return readBuffer;
	}
	
	public ByteBuffer getWriteBuffer()
	{
		return writeBuffer;
	}
	
	public boolean isHandlingRequest()
	{
		return (curMeRequest != null);
	}
	
	public int getPeerValue()
	{
		return peerValue;
	}
	
	public void addRequests(List<Request> requests)
	{
		//Adding all of the requests to the queue
		meRequests.addAll(requests);
		
		//Setting up the piece buffer
		mePieceBuffer = ByteBuffer.allocate(info.getPieceLength());
		
		//Sending the request to the peer
		curMeRequest = meRequests.poll();
		
		meBlockBuffer = ByteBuffer.allocate(curMeRequest.getBlockLength());
		
		ByteBuffer header = ByteBuffer.allocate(12);
		header.order(ByteOrder.BIG_ENDIAN);
		header.putInt(curMeRequest.getPieceIndex());
		header.putInt(curMeRequest.getBlockOffset());
		header.putInt(curMeRequest.getBlockLength());
		
		writeMessage(MessageType.REQUEST, header);
	}
	
	public void writeMessage(MessageType type, ByteBuffer payload)
	{
		if (payload != null)
		{
			payload.flip();
			writeMsgBuffer.putInt(1 + payload.remaining()).put((byte)type.valueOf()).put(payload);
			payload.compact();
		}
		else
		{
			writeMsgBuffer.putInt(1).put((byte)type.valueOf());
		}
	}
	
	public boolean checkHandshake()
	{
		//Flipping the buffer to read the data
		readBuffer.flip();
						
		//Dropping the connection if invalid name length
		if (readBuffer.get() != 19)
		{
			readBuffer.clear();
			return false;
		}
		
		//Dropping the connection if invalid protocol name
		byte[] name = new byte[19];
		readBuffer.get(name);
		for (int b = 0; b < 19; b++)
		{
			if (protocolName[b] != name[b])
			{
				readBuffer.clear();
				return false;
			}
		}
		
		//Skipping over the next 8 reserved bytes
		readBuffer.getDouble();
		
		//Getting the info hash and peer ID
		byte[] infoHash = new byte[20];
		readBuffer.get(infoHash);
		readBuffer.get(peerID);
		
		//Dropping the connection if the info hash does not match
		if (!Arrays.equals(info.getInfoHash(), infoHash))
		{
			readBuffer.clear();
			return false;
		}
		
		readBuffer.compact();
		return true;
	}
	
	/**
		Sets up the write buffer based on messages or piece data
	*/
	public void setupWrites() throws Exception
	{
		//Currently sending a block to the peer, so copy data from the bock buffer
		if (isSendingBlock)
		{
			peerBlockBuffer.flip();
			writeBuffer.put(peerBlockBuffer);
			peerBlockBuffer.compact();
			
			//No more block data to send, so end the current request
			if (peerBlockBuffer.position() == 0)
			{
				curPeerRequest = null;
				isSendingBlock = false;
			}
		}
		//Not sending a block, so copy messages from the message buffer (and possibly start another request)
		else
		{
			curPeerRequest = peerRequests.poll(); 
			
			if (curPeerRequest != null)
			{
				int blockLength = curPeerRequest.getBlockLength();
				int pieceIndex = curPeerRequest.getPieceIndex();
				int blockOffset = curPeerRequest.getBlockOffset();
			
				//Calculate which file the piece belongs to
				if (info instanceof SingleFileInfo)
				{
					SingleFileInfo infoAlias = (SingleFileInfo)info;
					
					byte[] block = new byte[blockLength];
					FileInputStream f = new FileInputStream(new File(infoAlias.getName()));
					f.read(block, pieceIndex * info.getPieceLength() + blockOffset, blockLength);
					
					peerBlockBuffer = ByteBuffer.wrap(block);
					
					ByteBuffer header = ByteBuffer.allocate(8);
					header.order(ByteOrder.BIG_ENDIAN);
					header.putInt(pieceIndex);
					header.putInt(blockOffset);
					
					writeMessage(MessageType.PIECE, header);
				}
				else if (info instanceof MultiFileInfo)
				{
					throw new Exception("Multifile not implemented");
				}
				
				isSendingBlock = true;
			}
			
			writeMsgBuffer.flip();
			writeBuffer.put(writeMsgBuffer);
			writeMsgBuffer.compact();
		}
	}
	
	/**
		Handles messages that were received from the peer
	*/
	public void handleMessages() throws Exception
	{
		//Flipping the buffer to read from it
		readBuffer.flip();
		
		//Currently receiving a block from the peer
		if (isReceivingBlock)
		{
			meBlockBuffer.put(readBuffer);
			
			//Block was fully downloaded from the peer
			if (!meBlockBuffer.hasRemaining())
			{
				//Putting the block in the piece buffer
				mePieceBuffer.put(meBlockBuffer);
				
				//Piece was fully downloaded from the peer
				if (!mePieceBuffer.hasRemaining())
				{
					//TODO: Calculate SHA-1 hash to ensure correctness
				
					//Calculate which file the piece belongs to
					if (info instanceof SingleFileInfo)
					{
						SingleFileInfo infoAlias = (SingleFileInfo)info;
						FileOutputStream f = new FileOutputStream(new File(infoAlias.getName()));
						f.write(mePieceBuffer.array(), curMeRequest.getPieceIndex() * info.getPieceLength(), info.getPieceLength());
					}
					else if (info instanceof MultiFileInfo)
					{
						throw new Exception("Multifile not implemented");
					}
				}
				
				//Starting a new request if possible
				curMeRequest = meRequests.poll();
				if (curMeRequest != null)
				{
					meBlockBuffer = ByteBuffer.allocate(curMeRequest.getBlockLength());
			
					ByteBuffer header = ByteBuffer.allocate(12);
					header.order(ByteOrder.BIG_ENDIAN);
					header.putInt(curMeRequest.getPieceIndex());
					header.putInt(curMeRequest.getBlockOffset());
					header.putInt(curMeRequest.getBlockLength());
			
					writeMessage(MessageType.REQUEST, header);
				}
			}
		}
		//Going through all of the messages read from the peer
		else
		{
			while (readBuffer.remaining() >= 4)
			{
				int messageLength = readBuffer.getInt();
				System.out.println("\tMessage Length: " + messageLength);
			
				//Keep alive message
				if (messageLength == 0)
				{
					continue;
				}
			
				//Not enough available to read the message type, so wait until next time
				if (readBuffer.remaining() == 0)
				{
					readBuffer.position(readBuffer.position() - 4);
					break;
				}
			
				MessageType messageID = MessageType.fromInt(readBuffer.get());
				System.out.println("\tMessage ID: " + messageID);
	
				//Making sure that the buffer has the full message (or atleast enough for piece message information)
				if ((messageLength - 1) <= readBuffer.remaining() || 
					((messageID == MessageType.PIECE) && ((messageLength - 1) <= 8)))
				{
					//Handling the message based on the ID
					switch (messageID)
					{
						case CHOKE:
							peerChoking = true;
							break;
					
						case UNCHOKE:
							peerChoking = false;
							break;
					
						case INTERESTED:
							peerInterested = true;
							break;
					
						case UNINTERESTED:
							peerInterested = false;
							break;
					
						case HAVE:
						{
							int piece = readBuffer.getInt();
							pieces.set(piece); 
							break;
						}
					
						case BITFIELD:
						{
							byte[] bitField = new byte[messageLength - 1];
							readBuffer.get(bitField);
							pieces.clear();
							
							int totalPieces = info.getPieces().length / 20;
							int pieceNum = 0;
							for (byte b : bitField)
							{
								for (int c = 7; (c > 0) && (pieceNum < totalPieces); c--)
								{
									if ((b & (1 << c)) != 0)
									{
										pieces.set(pieceNum);
									}
									pieceNum++;
								}							
							}
					
							//newbie to the swarm, give him good value
							//also give good value if the peer is close to finishing?
							if (pieces.nextSetBit(0) != -1)
							{
								peerValue = 0;
							}
							break;
						}
					
						case REQUEST:
						{
							int pieceIndex = readBuffer.getInt();
							int blockOffset = readBuffer.getInt();
							int blockLength = readBuffer.getInt();
					
							byte block[] = new byte[blockLength];
					
						
							if (meChoking) //Don't honour request if choked
							{
								break;
							}
							else if (blockLength > 128 * 1024) //if block length >128kb drop connection
							{
								throw new Exception("Requested blocksize too big"); //TODO: In torrent.java make exceptions drop connections
							}
						
							++peerValue;
							if (!peerRequests.offer(new Request(pieceIndex, blockOffset, blockLength)))
								throw new Exception("request queue ran out of memory");
						
							break;
						}
					
						case PIECE:
						{
							isReceivingBlock = true;
							--peerValue;
							return;
						}
					
						case CANCEL:
						{
							int pieceIndex = readBuffer.getInt();
							int blockOffset = readBuffer.getInt();
							int blockLength = readBuffer.getInt();
					
							peerRequests.remove(new Request(pieceIndex, blockOffset, blockLength));
							break;
						}
					}
				}
				else
				{
					readBuffer.position(readBuffer.position() - 5);
					break;
				}
			}
		}
		
		//Enabling the buffer to be written to again
		readBuffer.compact();
	}
	
	@Override
	public boolean equals(Object obj)
	{
		if (obj == null)
		{
			return false;
		}
		if (getClass() != obj.getClass())
		{
			return false;
		}
		final Peer other = (Peer) obj;
		if (!peerID.equals(other.peerID))
		{
			return false;
		}
		return true;
	}

	@Override
	public int hashCode()
	{
		int hash = 3;
		hash = 53 * hash + Arrays.hashCode(peerID);
		return hash;
	}

	
}
