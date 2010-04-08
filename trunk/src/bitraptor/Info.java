package bitraptor;

import java.util.*;
import java.net.*;

public class Info
{
	//Information about the torrent
	URL announceUrl;
	byte[] infoHash;
	List<List> announceLists = new java.util.ArrayList<List>();
	long creationDate = 0;
	String comment = null, createdBy = null, encoding = null;
	
	int pieceLength; 		//Size of each piece
	byte [] pieces; 		//SHA1 hashes of of pieces
	boolean privateTorrent; 	//If true, can obtain peers only via tracker (i.e. can't use DHT etc.)
	
	//Single File Mode Information
	String name = null; 	//Filename
	int fileLength; 	//File size
	byte [] md5sum; 	//MD5 hash of file
	
	public Info ()
	{
	}
}