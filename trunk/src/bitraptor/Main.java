package bitraptor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.io.FileInputStream;
import org.klomp.snark.bencode.BDecoder;

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
		
		
		BDecoder decoder = null;
		
		try
		{
			new BDecoder(new FileInputStream(f));
		}
		catch (java.io.FileNotFoundException e) //should never happen since f exists and is readable
		{
			System.out.println("Ok so this error shouldn't have happened.  I screwed up.  Are you sure some other process didn't delete the file in the past few microseconds?");
			System.out.println(e);
		}
		
		//TODO write code to download torrent
	}
   
}
