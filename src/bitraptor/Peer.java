package bitraptor;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;

public class Peer
{
	private static byte[] protocolName = {'B', 'i', 't', 'T', 'o', 'r', 'r', 'e', 'n', 't', ' ', 'p', 'r', 'o', 't', 'o', 'c', 'o', 'l'};
	
	private Info info;
	private byte[] peerID;
	private SocketChannel sock;
	private InetSocketAddress sockAddr;
	private boolean meChoking, meInterested;
	private boolean peerChoking, peerInterested;
	private BitSet pieces;
	private ByteBuffer readBuffer, writeBuffer;
	
	//Variables for handling requests
	private LinkedList<Request> peerRequests;
	private Request curPeerRequest;
	private LinkedList<Request> meRequests;
	private Request curMeRequest;
	
	//Holds the blocks that the peer sends
	//private Map<int, byte[]> blockBuffer;
	
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
		peerRequests = new LinkedList<Request>();
		curPeerRequest = null;
		meRequests = new LinkedList<Request>();
		curMeRequest = null;
		
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
		meChoking = choking;
	}
	
	public void setInterested(boolean interested)
	{
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
	
	public void addRequests(List<Request> requests)
	{
		meRequests.addAll(requests);
		
		curMeRequest = meRequests.poll();
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
		Handles all messages that are in the peer's queue
	*/
	public void handleMessages() throws Exception
	{
		//Flipping the buffer to read from it
		readBuffer.flip();
		
		while (readBuffer.hasRemaining())
		{
			int messageLength = readBuffer.getInt();
			
			System.out.println("\t[MESSAGE LENGTH] " + messageLength);
			
			//Keep alive message
			if(messageLength == 0)
			{
				continue;
			}
	
			//Making sure that the buffer has the full message 
			//TODO: It will never have full piece messages... so like, we gotta do something!
			if (messageLength <= readBuffer.remaining())
			{
				int messageID = readBuffer.get();

				System.out.println("\t[MESSAGE ID] " + messageID);

				//Handling the message based on the ID
				switch (messageID)
				{
					case 0: //Choke
						peerChoking = true;
						break;
					
					case 1: //Unchoke
						peerChoking = false;
						break;
					
					case 2: //Interested
						peerInterested = true;
						break;
					
					case 3: //Uninterested
						peerInterested = false;
						break;
					
					case 4: //Have
					{
						int piece = readBuffer.getInt();
						pieces.set(piece); 
						break;
					}
					
					case 5: //Bitfield
					{
						byte[] bitField= new byte[messageLength - 1];
						readBuffer.get(bitField);
						pieces.clear();
						
						int pieceNum = 0;
						int totalPieces = info.getPieces().length / 20;
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
					
						break;
					}
					
					case 6: //Request
					{
						int pieceIndex = readBuffer.getInt();
						int blockOffset = readBuffer.getInt();
						int blockLength = readBuffer.getInt(); //these will be class vars in a sec
					
						byte block[] = new byte[blockLength];
					
						//TODO honor request iff not choked
					
						//calculate which file the piece belongs to
						if (info instanceof SingleFileInfo)
						{
						
							SingleFileInfo infoAlias = (SingleFileInfo)info;
							FileInputStream f = new FileInputStream(new File(infoAlias.getName()));
							f.read(block, pieceIndex * info.getPieceLength() + blockOffset, blockLength);
					
						
							//TODO change later
							writeBuffer.putInt(9 + blockLength); //size of message
							writeBuffer.putInt(7); //type of message
							writeBuffer.putInt(pieceIndex).putInt(blockOffset); //payload headers
							writeBuffer.put(block); //payload data
						}
						else if (info instanceof MultiFileInfo)
						{
							MultiFileInfo infoAlias = (MultiFileInfo)info;
							List<SingleFileInfo> fileNames = infoAlias.getFiles();
							throw new Exception("Multifile not implemented");
						}
						else
						{
							throw new ClassCastException("Didn't expect Info type: " + info.getClass().getName());
						}
						
						break;
					}
					
					case 7: //Piece
					{
						int pieceIndex = readBuffer.getInt();
						int blockOffset = readBuffer.getInt();
						int blockLength = messageLength - 1 - 4 - 4;
						byte block[] = new byte[blockLength];
						readBuffer.get(block); //TODO: Non blocking call, so we need similar functionality for handling requests as with receiving pieces
						//TODO verify the hash of the piece
						if (info instanceof SingleFileInfo)
						{
							SingleFileInfo infoAlias = (SingleFileInfo)info;
							FileOutputStream f = new FileOutputStream(new File(infoAlias.getName()));
							f.write(block, pieceIndex * info.getPieceLength() + blockOffset, blockLength);
						
						}
						else if (info instanceof MultiFileInfo)
						{
							MultiFileInfo infoAlias = (MultiFileInfo)info;
							List <SingleFileInfo> fileNames = infoAlias.getFiles();
							throw new Exception("Multifile not implemented");
						}
						else
						{
							throw new ClassCastException("Didn't expect Info type: " + info.getClass().getName());
						}
					
						//TODO send HAVE to all other peers
						break;
					}
					
					case 8: //Cancel
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
				readBuffer.position(readBuffer.position() - 4);
				break;
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
