package bitraptor;

import java.util.Arrays;

public class Peer
{
	private boolean choked, interested;
	private byte[] ipAddr;
	private int port;


	
	public Peer(byte[] name, int port)
	{
		this(name, port, false, false);
	}
	
	public Peer(byte[] name, int port, boolean choked, boolean interested)
	{
		this.choked = choked;
		this.interested = interested;
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
	
	public byte[] getIpAddr() {
		return ipAddr;
	}

	public void setIpAddr(byte[] ipAddr) {
		this.ipAddr = ipAddr;
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		final Peer other = (Peer) obj;
		if (this.choked != other.choked) {
			return false;
		}
		if (this.interested != other.interested) {
			return false;
		}
		if (!Arrays.equals(this.ipAddr, other.ipAddr)) {
			return false;
		}
		if (this.port != other.port) {
			return false;
		}
		return true;
	}

	@Override
	public int hashCode() {
		int hash = 3;
		hash = 53 * hash + Arrays.hashCode(this.ipAddr);
		return hash;
	}

	
}