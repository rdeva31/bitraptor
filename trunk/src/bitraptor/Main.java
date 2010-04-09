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
		System.out.println("BitRaptor -- Makes your penis longer");
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
		Info info = new SingleFileInfo(); //declare as a singlefile info, change later
		
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

					if (infoDictionary.containsKey("files")) //multiple files mode
					{
						info = new MultiFileInfo(info);
						MultiFileInfo infoAlias = (MultiFileInfo)info;
						
						infoAlias.setDirectory(new String(infoDictionary.get("name").getBytes()));
						infoAlias.setFiles(new ArrayList<SingleFileInfo>());
						
						List<BEValue> fileDictionaries = infoDictionary.get("files").getList();
						for (BEValue fileDictionary : fileDictionaries)
						{
							Map<String, BEValue> fileDictionaryMap = fileDictionary.getMap();
							SingleFileInfo fileInfo = new SingleFileInfo();

							//name and path
							List<BEValue> paths = fileDictionaryMap.get("path").getList();
							String filePath = null;
							
							for (int c = 0; c < paths.size(); ++c)
							{
								if (c == 0)
									filePath = new String(paths.get(c).getBytes());
								else if (c != paths.size() - 1)
									filePath += "/" + new String(paths.get(c).getBytes());
							}

							fileInfo.setName(filePath);


							//file size
							fileInfo.setFileLength(fileDictionaryMap.get("length").getInt());

							//md5sum of file
							if (fileDictionaryMap.containsKey("md5sum"))
								fileInfo.setMd5sum(fileDictionaryMap.get("md5sum").getBytes());

							//add to file to directory
							infoAlias.getFiles().add(fileInfo);
						}
						
					}
					else if (infoDictionary.containsKey("length"))	//single file mode
					{
						info = new SingleFileInfo(info);
						SingleFileInfo infoAlias = (SingleFileInfo)info;


						//Name
						infoAlias.setName(new String(infoDictionary.get("name").getBytes()));

						//Length
						infoAlias.setFileLength(infoDictionary.get("length").getInt());

						//MD5 Checksum
						if (infoDictionary.containsKey("md5sum"))
							infoAlias.setMd5sum(infoDictionary.get("md5sum").getBytes());

					}
					else
						throw new Exception("Invalid torrent file.  info doesn't contain files or length");
					
					//Hash of 'info' field
					info.setInfoHash(decoder.get_special_map_digest());

					//These are common to both SingleFileInfo and MultifileInfo
					//Piece Length
					info.setPieceLength(infoDictionary.get("piece length").getInt());

					//Privateinfo.announceUrl
					if (infoDictionary.containsKey("private"))
						info.setPrivateTorrent((infoDictionary.get("private").getInt() == 1) ? true : false);
					else
						info.setPrivateTorrent(false);

					//Pieces
					info.setPieces(infoDictionary.get("pieces").getBytes());
				}
				//Announce
				else if (field.equalsIgnoreCase("announce"))
				{
					if (info.getAnnounceUrls() == null)
						info.setAnnounceUrls(new ArrayList<URL>());
					info.getAnnounceUrls().add(0, new URL(new String(torrentFileMappings.get(field).getBytes()))); //always want announce URL to be top choice
				}
				//Announce List
				else if (field.equalsIgnoreCase("announce-list"))
				{
					List<BEValue> announceLists = torrentFileMappings.get(field).getList();
					
					if (info.getAnnounceUrls() == null)
						info.setAnnounceUrls(new ArrayList<URL>());
						
					for (BEValue b : announceLists)
					{
						List<BEValue> l = b.getList(); 
						for (BEValue announceUrl : l)
						{
							URI u = new URI(new String(announceUrl.getBytes()));
							if (u.getScheme().equalsIgnoreCase("http"))
								info.getAnnounceUrls().add(u.toURL());
						}
					}
				}
				//Creation Date
				else if (field.equalsIgnoreCase("creation date"))
				{
					info.setCreationDate(torrentFileMappings.get(field).getLong());
				}
				//Comment
				else if (field.equalsIgnoreCase("comment"))
				{
					info.setComment(new String(torrentFileMappings.get(field).getBytes()));
				}
				//Created-By
				else if (field.equalsIgnoreCase("created by"))
				{
					info.setCreatedBy(new String(torrentFileMappings.get(field).getBytes()));
				}
				//Encoding
				else if (field.equalsIgnoreCase("encoding"))
				{
					info.setEncoding(new String(torrentFileMappings.get(field).getBytes()));
				}
			}
		}
		catch (Exception e)
		{
			System.out.println("ERROR: Invalid torrent file");
			e.printStackTrace();
			return;
		}
		
		////////////////////////////////////////////////////////////////
		////////////////////////////////////////////////////////////////
		System.out.println("AnnounceURL : " + info.getAnnounceUrls());
		System.out.println("Torrent Created By " + info.getCreatedBy() + " on " + info.getCreationDate() +" with encoding " + info.getEncoding());
		System.out.println("Comments: " + info.getComment());
		System.out.println("Info:");
		System.out.println("\tPiece Length : " + info.getPieceLength());
		System.out.println("\tPrivate: " + info.isPrivateTorrent());
		System.out.println(info.toString());
		//System.out.print("\tFile Name: " + info.name + "(" + info.fileLength + " bytes)");
//		if (info.getMd5sum() == null)
//		{
//			System.out.println(" with NO md5sum");
//		}
//		else
//		{
//			System.out.println(" with md5sum " + info.ge);
//		}
//		System.out.println("\tHashes for " + info.getPieces().length + " pieces");
//		System.out.println("\tInfo hash: " + info.getInfoHash());
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
