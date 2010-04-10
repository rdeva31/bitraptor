package bitraptor;

import java.util.Arrays;

public class Peer
{
	private String peerID;
	private String IPAddr;
	private int port;
	private boolean choked, interested;

	public Peer(String IPAddr, int port)
	{
		this("", IPAddr, port, false, false);
	}
	
	public Peer(String peerID, String IPAddr, int port)
	{
		this(peerID, IPAddr, port, false, false);
	}
	
	public Peer(String peerID, String IPAddr, int port, boolean choked, boolean interested)
	{
		this.peerID = peerID;
		this.IPAddr = IPAddr;
		this.port = port;
		this.choked = choked;
		this.interested = interested;
	}
	
	public String getPeerID()
	{
		return peerID;
	}

	public void setPeerID(String peerID)
	{
		this.peerID = peerID;
	}
	
	public String getIPAddr()
	{
		return IPAddr;
	}

	public void setIPAddr(String IPAddr)
	{
		this.IPAddr = IPAddr;
	}

	public int getPort()
	{
		return port;
	}

	public void setPort(int port)
	{
		this.port = port;
	}
	
	public void setChoked(boolean choked)
	{
		this.choked = choked;
	}
	
	public void setInterested(boolean interested)
	{
		this.interested = interested;
	}
	
	public boolean isChoked()
	{
		return choked;
	}
	
	public boolean isInterested()
	{
		return interested;
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
		if (!this.peerID.equals(other.peerID))
		{
			return false;
		}
		if (!this.IPAddr.equals(other.IPAddr))
		{
			return false;
		}
		if (this.port != other.port)
		{
			return false;
		}
		return true;
	}

	@Override
	public int hashCode()
	{
		int hash = 3;
		hash = 53 * hash + Arrays.hashCode(this.IPAddr.getBytes());
		return hash;
	}

	
}