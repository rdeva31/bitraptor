package bitraptor;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.Arrays;

public class Peer
{
	private static byte[] protocolName = {'B', 'i', 't', 'T', 'o', 'r', 'r', 'e', 'n', 't', ' ', 'p', 'r', 'o', 't', 'o', 'c', 'o', 'l'};
	
	private Info info;
	private byte[] peerID;
	private SocketChannel sock;
	private InetSocketAddress sockAddr;
	private boolean meChoking, meInterested;
	private boolean peerChoking, peerInterested;
	
	public Peer(byte[] peerID, SocketChannel sock)
	{
		this.peerID = peerID;
		this.sock = sock;
		this.sockAddr = (InetSocketAddress)(sock.socket().getRemoteSocketAddress());
		this.meChoking = true;
		this.meInterested = false;
		this.peerChoking = true;
		this.peerInterested = false;
	}
	
	public Peer(byte[] peerID, String IPAddr, int port)
	{
		this.peerID = peerID;
		this.sock = null;
		this.meChoking = true;
		this.meInterested = false;
		this.peerChoking = true;
		this.peerInterested = false;
		
		try
		{
			sockAddr = new InetSocketAddress(IPAddr, port);
		}
		catch (IllegalArgumentException e)
		{
			sockAddr = null;
		}
	}
	
	public void connect() throws IOException
	{
		//Setting up the socket connection if necessary
		if (sock == null)
		{
			sock = SocketChannel.open();
			sock.configureBlocking(false);
			sock.connect(sockAddr);
		}
	}
	
	public void write(ByteBuffer buffer) throws IOException, ClosedChannelException
	{
		while (buffer.hasRemaining())
		{
			sock.write(buffer);
		}
	}
	
	public byte[] getPeerID()
	{
		return peerID;
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
		if (sockAddr == null || other.sockAddr == null || !sockAddr.equals(other.sockAddr))
		{
			return false;
		}
		return true;
	}

	@Override
	public int hashCode()
	{
		int hash = 3;
		hash = 53 * hash + Arrays.hashCode(((InetSocketAddress)sock.socket().getRemoteSocketAddress()).getAddress().getAddress());
		return hash;
	}

	
}
