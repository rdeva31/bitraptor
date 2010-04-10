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
		System.out.println("BitLaptor -- Makes Japanese people run for their lives... Godzilla all over again");
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
		catch (FileNotFoundException e)
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

					//Multiple Files Mode
					if (infoDictionary.containsKey("files"))
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

							//Name and path
							List<BEValue> paths = fileDictionaryMap.get("path").getList();
							String filePath = null;
							
							for (int c = 0; c < paths.size(); c++)
							{
								if (c == 0)
									filePath = new String(paths.get(c).getBytes());
								else if (c != paths.size() - 1)
									filePath += "/" + new String(paths.get(c).getBytes());
							}

							fileInfo.setName(filePath);


							//File size
							fileInfo.setFileLength(fileDictionaryMap.get("length").getInt());

							//MD5 checksum
							if (fileDictionaryMap.containsKey("md5sum"))
							{
								fileInfo.setMd5sum(fileDictionaryMap.get("md5sum").getBytes());
							}

							//Adding the file to the directory
							infoAlias.getFiles().add(fileInfo);
						}
						
					}
					//Single File Mode
					else if (infoDictionary.containsKey("length"))
					{
						info = new SingleFileInfo(info);
						SingleFileInfo infoAlias = (SingleFileInfo)info;


						//Name
						infoAlias.setName(new String(infoDictionary.get("name").getBytes()));

						//Length
						infoAlias.setFileLength(infoDictionary.get("length").getInt());

						//MD5 Checksum
						if (infoDictionary.containsKey("md5sum"))
						{
							infoAlias.setMd5sum(infoDictionary.get("md5sum").getBytes());
						}

					}
					else
						throw new Exception("Invalid torrent file.  info doesn't contain files or length");
					
					//Hash of 'info' field
					info.setInfoHash(decoder.get_special_map_digest());

					//Pieces
					info.setPieces(infoDictionary.get("pieces").getBytes());

					//Piece Length
					info.setPieceLength(infoDictionary.get("piece length").getInt());

					//Private
					if (infoDictionary.containsKey("private"))
					{
						info.setPrivateTorrent((infoDictionary.get("private").getInt() == 1) ? true : false);
					}
					else
					{
						info.setPrivateTorrent(false);
					}
				}
				//Announce
				else if (field.equalsIgnoreCase("announce"))
				{
					if (info.getAnnounceUrls() == null)
					{
						info.setAnnounceUrls(new ArrayList<URL>());
					}
					
					//'Announce' URL is top priority URL in the list
					info.getAnnounceUrls().add(0, new URL(new String(torrentFileMappings.get(field).getBytes())));
				}
				//Announce List
				else if (field.equalsIgnoreCase("announce-list"))
				{
					List<BEValue> announceLists = torrentFileMappings.get(field).getList();
					
					if (info.getAnnounceUrls() == null)
					{
						info.setAnnounceUrls(new ArrayList<URL>());
					}
					
					for (BEValue b : announceLists)
					{
						List<BEValue> l = b.getList(); 
						for (BEValue announceUrl : l)
						{
							//Only working with trackers that are contacted over HTTP
							URI u = new URI(new String(announceUrl.getBytes()));
							if (u.getScheme().equalsIgnoreCase("http"))
							{
								info.getAnnounceUrls().add(u.toURL());
							}
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
		//Invalid torrent file (Could not be parsed)
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
