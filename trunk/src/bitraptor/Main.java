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
		System.out.println("BitRaptor -- Takes a BITE out of crime");
		System.out.println("(Type 'help' to see available commands)");
		
		BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
		while (true)
		{
			String[] command;
			
			//Prompting for and reading user input
			System.out.print("> ");
			
			try
			{
				command = in.readLine().trim().split(" ");
			}
			catch (Exception e)
			{
				System.err.println(e);
				return;
			}
			
			//Executing the specified command
			if (command[0].equalsIgnoreCase("help"))
			{
				new Main().handleHelp();
			}
			else if (command[0].equalsIgnoreCase("exit"))
			{
				return;
			}
			else if (command[0].equalsIgnoreCase("download") || command[0].equalsIgnoreCase("dl") ||
				command[0].equalsIgnoreCase("steal"))
			{
				try
				{
					//No torrent file specified
					if (command[1] == null)
					{
						throw new IndexOutOfBoundsException();
					}
				}
				catch (IndexOutOfBoundsException e)
				{
					System.out.println("Specify the torrent file");
					continue;
				}
				
				new Main().handleDownload(new File(command[1]));
			}
			else
			{
				System.out.println("Invalid command. Type 'help' to see available commands.");
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
		str += "\tdownload <Torrent File> -- Downloads the files associated with the torrent [NOTE: No spaces in file name]\n";
		str += "\texit -- Exits the BitRaptor program\n";
		
		System.out.println(str);
	}
	
	/**
		Helper function for the "download" command used by main().
		Assumes that file != null.  If file is null, just returns without doing anything.
		
		@param file - The torrent file to read from
	*/
	private void handleDownload (File file)
	{
		//Checking if the torrent file exists, is a file, and is readable
		if (file == null)
		{
			return;
		}
		else if (!file.exists())
		{
			System.out.println("ERROR: " + file.getAbsolutePath() + " does not exist.");
			return;
		}
		else if (!file.isFile())
		{
			System.out.println("ERROR: " + file.getAbsolutePath() + " is not a valid file.");
			return;
		}
		else if (!file.canRead())
		{
			System.out.println("ERROR: " + file.getAbsolutePath() + " is not open for read access.");
			return;
		}
		
		//Begin parsing the torrent file
		BDecoder decoder = null;
		BEValue value = null;
		Info info = new Info();
		
		try
		{
			decoder = new BDecoder(new FileInputStream(file));
		}
		catch (java.io.FileNotFoundException e)
		{
			System.out.println("ERROR: " + file.getAbsolutePath() + " does not exist.");
			return;
		}
		
		try
		{
			Map<String, BEValue> torrentFileMappings = decoder.bdecode().getMap();
			Set<String> fields = torrentFileMappings.keySet();
			
			//Handling each field in the file
			for (String field : fields)
			{
				//Info
				if (field.equalsIgnoreCase("info"))
				{
					Map<String, BEValue> infoDictionary = torrentFileMappings.get(field).getMap();
					
					//Piece Length
					info.pieceLength = infoDictionary.get("piece length").getInt();
					
					//Private
					if (infoDictionary.containsKey("private"))
						info.privateTorrent = (infoDictionary.get("private").getInt() == 1) ? true : false;
					else
						info.privateTorrent = false;
						
					//Pieces
					info.pieces = infoDictionary.get("pieces").getBytes();;
					
					//Name
					info.name = new String(infoDictionary.get("name").getBytes());
					
					//Length
					info.fileLength = infoDictionary.get("length").getInt();
					
					//MD5 Checksum
					if (infoDictionary.containsKey("md5sum"))
						info.md5sum = infoDictionary.get("md5sum").getBytes();
						
					//Hash of 'info' field
					info.infoHash = decoder.get_special_map_digest();
					
				}
				//Announce
				else if (field.equalsIgnoreCase("announce"))
				{
					info.announceUrl = new URL(new String(torrentFileMappings.get(field).getBytes()));
				}
				//Announce List
				else if (field.equalsIgnoreCase("announce-list"))
				{
					//TODO: Add support for announce list
				}
				//Creation Date
				else if (field.equalsIgnoreCase("creation date"))
				{
					info.creationDate = torrentFileMappings.get(field).getLong();
				}
				//Comment
				else if (field.equalsIgnoreCase("comment"))
				{
					info.comment = new String(torrentFileMappings.get(field).getBytes());
				}
				//Created-By
				else if (field.equalsIgnoreCase("created by"))
				{
					info.createdBy = new String(torrentFileMappings.get(field).getBytes());
				}
				//Encoding
				else if (field.equalsIgnoreCase("encoding"))
				{
					info.encoding = new String(torrentFileMappings.get(field).getBytes());
				}
			}
		}
		catch (Exception e)
		{
			System.out.println("ERROR: Invalid torrent file");
			return;
		}
		
		////////////////////////////////////////////////////////////////
		////////////////////////////////////////////////////////////////
		System.out.println("AnnounceURL : " + info.announceUrl);
		System.out.println("AnnounceURL-Lists : " + info.announceLists.toString());
		System.out.println("Torrent Created By " + info.createdBy + " on " + info.creationDate +" with encoding " + info.encoding);
		System.out.println("Comments: " + info.comment);
		System.out.println("Info:");
		System.out.println("\tPiece Length : " + info.pieceLength);
		System.out.println("\tPrivate: " + info.privateTorrent);
		System.out.print("\tFile Name: " + info.name + "(" + info.fileLength + " bytes)");
		if (info.md5sum == null)
		{
			System.out.println(" with NO md5sum");
		}
		else
		{
			System.out.println(" with md5sum " + info.md5sum);
		}
		System.out.println("\tHashes for " + info.pieces.length + " pieces");
		System.out.println("\tInfo hash: " + info.infoHash);
		////////////////////////////////////////////////////////////////
		////////////////////////////////////////////////////////////////
		
		//TODO: Starting a new thread for the torrent
		/*
		new Thread(new Runnable()
		{
			public void run()
			{
				Torrent torrent = new Torrent(info, 6881);
				
				torrent.start();
			}
		}).start();
		*/
		
		Torrent torrent = new Torrent(info, 6881);
		torrent.start();

	}
}
