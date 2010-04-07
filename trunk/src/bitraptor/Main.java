package bitraptor;

import java.io.*;
import java.util.*;
import java.net.*;
import org.klomp.snark.bencode.*;

public class Main {

	/**
		Starts the BitRaptor program.  No arguments required.
	 */
	public static void main(String[] args) {
		System.out.println("BitRaptor -- Possibly the crappiest bittorrent client you'll ever use (actually no, bitcomet is worse)\nType help for commands");
		
		BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
		while (true)
		{
			System.out.print("> ");
			String command;
			String[] commandFull;
			
			try
			{
				commandFull = in.readLine().trim().toLowerCase().split(" ");
			}
			catch (Exception e)
			{
				System.err.println(e);
				return;
			}
			
			command = commandFull[0];
			
			if (command.equals("help"))
				new Main().handleHelp();
			else if (command.equals("exit"))
				return;
			else if (command.equals("steal"))
			{
				try
				{
					if (commandFull[1] == null)
						throw new IndexOutOfBoundsException(); //let the catch handle this
				}
				catch (IndexOutOfBoundsException e)
				{
					System.out.println("Specify the torrent file");
					continue;
				}
				
				new Main().handleSteal(new File(commandFull[1]));
			}
			else
			{
				System.out.println("im a computer and what is this");
			}
		}
	}
	
	/**
		Helper function for the "help" command used by main()
	*/
	private void handleHelp()
	{
		String str = "";
		str += "Available commands are:\n";
		str += "\thelp -- prints this thing\n";
		str += "\tsteal file.torrent -- Downloads the files associated with the torrent file.torrent [Can't have spaces in the file name]\n";
		str += "\texit -- quits this program (something you'd never think of doing)\n";
		
		System.out.println(str);
	}
	
	/**
		Helper function for the "steal" command used by main().
		Assumes that f != null.  If f is null, just returns without doing anything.
		
		@param f - the .torrent file to read from
	*/
	private void handleSteal(File f)
	{
		//Variables detailing the attributes of the torrent and the file to be downloaded
		String announceUrl = null;
		List<List> announceLists = new java.util.ArrayList<List>();
		long creationDate = 0;
		String comment = null, createdBy = null, encoding = null;
		SingleFileInfo info = new SingleFileInfo();
		byte[] infoHash = null; //hash of the info segment
		
		if (f == null)
			return;
		else if (!f.exists())
		{
			System.out.println("Yo, I can't find " + f.getAbsolutePath());
			return;
		}
		else if (!f.isFile())
		{
			System.out.println(f.getAbsolutePath() + " ain't no file.");
			return;
		}
		else if (!f.canRead())
		{
			System.out.println("Yeah, um I can't read " + f.getAbsolutePath() + " (check permissions?) ");
			return;
		}
		
		/* Begin parsing the torrent file */
		BDecoder decoder = null;
		BEValue value = null;
		try
		{
			decoder = new BDecoder(new FileInputStream(f));
		}
		catch (java.io.FileNotFoundException e) //should never happen since f exists and is readable
		{
			System.out.println("Ok so this error shouldn't have happened.  I screwed up.  Are you sure some other process didn't delete the file in the past few microseconds?");
			System.out.println(e);
			return;
		}
		
		try
		{
			Map<String, BEValue> torrentFileMappings = decoder.bdecode().getMap();
			Set<String> fields = torrentFileMappings.keySet();
			
			for (String field : fields) //handle each field
			{
				if (field.equalsIgnoreCase("info"))
				{
					Map<String, BEValue> infoDictionary = torrentFileMappings.get(field).getMap();
					info.pieceLength = infoDictionary.get("piece length").getInt();
					
					if (infoDictionary.containsKey("private"))
						info.privateTorrent = (infoDictionary.get("private").getInt() == 1) ? true : false;
					else
						info.privateTorrent = false;
						
					info.pieces = infoDictionary.get("pieces").getBytes();;
					
					info.name = new String(infoDictionary.get("name").getBytes());
					info.fileLength = infoDictionary.get("length").getInt();
					
					if (infoDictionary.containsKey("md5sum"))
						info.md5sum = infoDictionary.get("md5sum").getBytes();

					infoHash = decoder.get_special_map_digest();
					
				}
				else if (field.equalsIgnoreCase("announce"))
				{
					announceUrl = new String(torrentFileMappings.get(field).getBytes());
				}
				else if (field.equalsIgnoreCase("announce-list"))
				{
					//while(decoder.getNextIndicator() != 'l')
					//	announceLists.add(decoder.bdecode().getList());
					throw new Exception("Need to implement announce-list");
				}
				else if (field.equalsIgnoreCase("creation-date"))
					creationDate = torrentFileMappings.get(field).getLong();
				else if (field.equalsIgnoreCase("comment"))
					comment = new String(torrentFileMappings.get(field).getBytes());
				else if (field.equalsIgnoreCase("created-by"))
					createdBy = new String(torrentFileMappings.get(field).getBytes());
				else if (field.equalsIgnoreCase("encoding"))
					encoding = new String(torrentFileMappings.get(field).getBytes());
				else
					;//throw new InvalidBEncodingException("unknown field: " + field); //don't throw this because apparently torrent files have fields
																						//not specified in the official docs
				
			}
		}
		catch (Exception e)
		{
			System.out.println("The torrent file is improperly constructed...just like yo mama");
			e.printStackTrace();
			return;
		}
		/* End parsing the torrent file */
		System.out.println("announceurl : " + announceUrl);
		System.out.println("announceurl-lists : " + announceLists.toString());
		System.out.println("Torrent created by " + createdBy + " on " + creationDate +" with encoding " + encoding);
		System.out.println("Comments: " + comment);
		
		System.out.println("info:");
		System.out.println("piece length : " + info.pieceLength);
		System.out.println("Is private? " + info.privateTorrent);
		System.out.print("File name: " + info.name + "(" + info.fileLength + " bytes)");
		if (info.md5sum == null)
			System.out.println(" with no md5sum");
		else
			System.out.println(" with md5sum " + info.md5sum);
		System.out.println("Have hashes for " + info.pieces.length + " pieces");
		System.out.println("Info hash: " + infoHash);
		//Now connect to the tracker and get a list of peers
		byte [] peerID = new byte[20];
		new Random().nextBytes(peerID);
		System.out.println("Tracker response:");
		
		try
		{
			System.out.println(announce(new URL(announceUrl), infoHash, peerID, 6881, 0, 0, info.fileLength, AnnounceEvent.STARTED));
		}
		catch (MalformedURLException e1)
		{
			System.out.println("Tracker url is malformed.");
			return;
		}
		catch (IOException e2)
		{
			System.out.println("I think the tracker is out for lunch");
			return;
		}
		catch (IndexOutOfBoundsException e3)
		{
			System.out.println("Damn, I think the hash is screwed up, so you're pretty much fucked.  Get a new torrent file");
			return;
		}
		
	}
	
	/**
		Announces the torrent information to the tracker and returns the tracker response.
		@param infoHash	SHA-1 hash of the info metadata in the torrent. Must be 20 bytes.
		@param peerID	A 20-byte unique identifier.  No restrictions on what this is
		@param port	The port number that the client is listening on. Typically in the range 6881-6889
		@param uploaded	The number of bytes uploaded by this client during this session
		@param downloaded	The number of bytes downloaded by this client during this session
		@param left	Number of bytes that the client needs to download
		@param event	Indicates the status of the connection
		@return The response of the tracker or null on error.
		@throws IOException	IOException thrown if unable to contact or read from server
		@throws IndexOutOfBoundsException	Thrown if the hash or peerID are not of sufficient length
	*/
	private String announce(URL announceUrl, byte[] infoHash, byte[] peerID, int port, 
		int uploaded, int downloaded, int left, AnnounceEvent event) throws IOException, IndexOutOfBoundsException
	{
		//Matt start working here
		URLConnection trackerUrl = announceUrl.openConnection();
		
		BufferedWriter trackerWriter;
		BufferedReader trackerReader;
		
		if (infoHash.length != 20)
			throw new IndexOutOfBoundsException("hash length not 20 bytes");
		if (peerID.length != 20)
			throw new IndexOutOfBoundsException("peerID length not 20 bytes");
			
		trackerWriter = new BufferedWriter(new OutputStreamWriter(trackerUrl.getOutputStream()));
		trackerReader = new BufferedReader(new InputStreamReader(trackerUrl.getInputStream()));
		
		String request = "GET " + announceUrl.getProtocol() + "://" +  announceUrl.getHost() + announceUrl.getPath() + "HTTP/1.1\r\n";
		
		System.out.println("Request is: " + request);
		
		return null;//shouldn't be returning null, just to make compiler stfu
		
	}
	
	private class Info
	{
		int pieceLength;
		byte [] pieces;
		boolean privateTorrent;
	}
	
	private class SingleFileInfo extends Info
	{
		String name;
		int fileLength;
		byte md5sum[];
	}
	
	private enum AnnounceEvent {
		STARTED,
		STOPPED,
		COMPLETED,
		NONE
	}
}
