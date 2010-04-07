package bitraptor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.io.FileInputStream;
import org.klomp.snark.bencode.BDecoder;
import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.bencode.InvalidBEncodingException;
import java.util.Map;
import java.util.List;

public class Main {

	/**
		Starts the BitRaptor program.  No arguments required.
	 */
	public static void main(String[] args) {
		System.out.println("BitRaptor -- possibly the crappiest bittorrent client you'll ever use (actually no, bitcomet is worse)\nType help for commands");
		
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
				handleHelp();
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
				
				handleSteal(new File(commandFull[1]));
			}
			else
				System.out.println("im a computer and what is this");
		}
	}
	
	/**
		Helper function for the "help" command used by main()
	*/
	private static void handleHelp()
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
	private static void handleSteal(File f)
	{
		//Variables detailing the attributes of the torrent and the file to be downloaded
		String announceUrl = null;
		List<List> announceLists = new java.util.ArrayList<List>();
		long creationDate = 0;
		String comment = null, createdBy = null, encoding = null;
		Info info = new Info();;
		
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
			while ((value = decoder.bdecode()) != null)
			{
				String field = null;
				field = new String(value.getBytes());
			
			
				if (field.equalsIgnoreCase("info"))
				{
					Map infoDictionary = bdecoder.bdecode().getMap();
					info.pieceLength = (long)infoDictionary.get("piece length");
					
					if (infoDictionary.containsKey("private"))
						info.privateTorrent = (infoDictionary.get("private") == 1) ? true : false;
					else
						info.privateTorrent = false;
						
					info.pieces = (byte []) infoDictionary.get("pieces");
				}
				else if (field.equalsIgnoreCase("announce"))
				{
					announceUrl = new String(decoder.bdecode().getBytes());
				}
				else if (field.equalsIgnoreCase("announce-list"))
				{
					while(decoder.getNextIndicator() != 'l')
						announceLists.add(decoder.bdecode().getList());
				}
				else if (field.equalsIgnoreCase("creation-date"))
					creationDate = decoder.bdecode().getLong();
				else if (field.equalsIgnoreCase("comment"))
					comment = new String(decoder.bdecode().getBytes());
				else if (field.equalsIgnoreCase("created-by"))
					createdBy = new String(decoder.bdecode().getBytes());
				else if (field.equalsIgnoreCase("encoding"))
					encoding = new String(decoder.bdecode().getBytes());
				else
					throw new InvalidBEncodingException("unknown field");
				
				
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
		System.out.println("Torrent created by " + createdBy + " on " + creationDate +" with encoding" + encoding);
		System.out.println(comment);
	}
	
	
	private class Info
	{
		long pieceLength;
		byte [] pieces;
		boolean privateTorrent;
	}
	
	private class SingleFileInfo extends Info
	{
		String info;
		long fileLength;
		byte md5sum[];
	}
}
