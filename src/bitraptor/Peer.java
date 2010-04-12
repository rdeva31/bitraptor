package bitraptor;

import java.util.Arrays;

public class Peer
{
	private String peerID;
	private String IPAddr;
	private int port;
	private boolean meChoking, meInterested;
	private boolean peerChoking, peerInterested;

	public Peer(String IPAddr, int port)
	{
		this("", IPAddr, port, true, false, true, false);
	}
	
	public Peer(String peerID, String IPAddr, int port)
	{
		this(peerID, IPAddr, port, true, false, true, false);
	}
	
	public Peer(String peerID, String IPAddr, int port, boolean meChoking, boolean meInterested, boolean peerChoking, boolean peerInterested)
	{
		this.peerID = peerID;
		this.IPAddr = IPAddr;
		this.port = port;
		this.meChoking = meChoking;
		this.meInterested = meInterested;
		this.peerChoking = peerChoking;
		this.peerInterested = peerInterested;
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
