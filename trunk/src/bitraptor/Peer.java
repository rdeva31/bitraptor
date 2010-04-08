package bitraptor;

public class Peer
{
	private boolean choked, interested;
	
	public Peer()
	{
		this(false, false);
	}
	
	public Peer(boolean choked, boolean interested)
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
}