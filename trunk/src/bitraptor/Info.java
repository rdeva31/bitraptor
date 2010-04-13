package bitraptor;

import java.util.*;
import java.net.*;

public abstract class Info
{
	//Information about the torrent
	private List<URL> announceUrls = null;
	private byte[] infoHash = null;
	private long creationDate = 0;
	private String comment = null, createdBy = null, encoding = null;
	private int pieceLength; 		//Size of each piece
	private byte [] pieces; 		//SHA1 hashes for each of the pieces
	private boolean privateTorrent; 	//If true, can obtain peers only via tracker (i.e. can't use DHT etc.)

	public Info()
	{

	}

	public Info(Info toCopy)
	{
		this.announceUrls = toCopy.announceUrls;
		this.infoHash = (toCopy.infoHash == null) ? null : Arrays.copyOf(toCopy.infoHash, toCopy.infoHash.length);
		this.creationDate = toCopy.creationDate;
		this.comment = toCopy.comment;
		this.createdBy = toCopy.createdBy;
		this.pieceLength = toCopy.pieceLength;
		this.pieces = (toCopy.pieces == null) ? null : Arrays.copyOf(toCopy.pieces, toCopy.pieces.length);
		this.privateTorrent = toCopy.privateTorrent;
	}

	public List<URL> getAnnounceUrls()
	{
		return announceUrls;
	}

	public void setAnnounceUrls(List<URL> announceUrls)
	{
		this.announceUrls = announceUrls;
	}

	public String getComment()
	{
		return comment;
	}

	public void setComment(String comment)
	{
		this.comment = comment;
	}

	public String getCreatedBy()
	{
		return createdBy;
	}

	public void setCreatedBy(String createdBy)
	{
		this.createdBy = createdBy;
	}

	public long getCreationDate()
	{
		return creationDate;
	}

	public void setCreationDate(long creationDate)
	{
		this.creationDate = creationDate;
	}

	public String getEncoding()
	{
		return encoding;
	}

	public void setEncoding(String encoding)
	{
		this.encoding = encoding;
	}

	public byte[] getInfoHash()
	{
		return infoHash;
	}

	public void setInfoHash(byte[] infoHash)
	{
		this.infoHash = Arrays.copyOf(infoHash, infoHash.length);
	}

	public int getPieceLength()
	{
		return pieceLength;
	}

	public void setPieceLength(int pieceLength)
	{
		this.pieceLength = pieceLength;
	}

	public byte[] getPieces()
	{
		return pieces;
	}

	public void setPieces(byte[] pieces)
	{
		this.pieces = Arrays.copyOf(pieces, pieces.length);
	}

	public boolean isPrivateTorrent()
	{
		return privateTorrent;
	}

	public void setPrivateTorrent(boolean privateTorrent)
	{
		this.privateTorrent = privateTorrent;
	}
	
	public abstract int getFileLength();

	@Override
	public String toString()
	{
		return "announceurls:" + announceUrls.toString() +
				"; infohash:" + (infoHash == null ? "null" : infoHash) +
				"; creationdate: " + //creationDate +
				"; comment: " + comment +
				"; createdby: " + createdBy +
				"; encoding:" + encoding +
				"; piece size: " + pieceLength +
				"; private torrent: " + privateTorrent +
				"; # of pieces : " + pieces.length;
	}
	
	
}
