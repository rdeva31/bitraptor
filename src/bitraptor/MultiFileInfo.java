package bitraptor;

import java.util.*;
import java.io.*;
import java.nio.*;

/**
 *
 * @author rdeva
 */
public class MultiFileInfo extends Info
{
	private String directory = null; //Directory name to store all files in
	private List<SingleFileInfo> files = null;
	private int fileLength = 0; 	//File size

	public MultiFileInfo()
	{

	}

	public MultiFileInfo(Info i)
	{
		super(i);
	}

	public MultiFileInfo(MultiFileInfo toCopy)
	{
		directory = toCopy.directory;
		files = new ArrayList<SingleFileInfo>();
		files.addAll(toCopy.files);
		
		for (SingleFileInfo file : files)
		{
			fileLength += file.getFileLength();
		}
	}

	public String getDirectory()
	{
		return directory;
	}

	public void setDirectory(String directory)
	{
		this.directory = directory;
	}

	/**
	 * Returns the files with in this directory.  Note that to find the exact path
	 * of a given file f, do this:
	 * SingleFileInfo f = getFiles().get(0);
	 * System.out.println(getDirectory() + "/" + f.getName() + " is the location of file");
	 * @return all files relative to the current directory
	 */
	public List<SingleFileInfo> getFiles()
	{
		return files;
	}

	public void setFiles(List<SingleFileInfo> files)
	{
		this.files = files;
		fileLength = 0;
		
		if (files != null)
		{
			for (SingleFileInfo file : files)
			{
				fileLength += file.getFileLength();
			}
		}
	}
	
	public int getFileLength()
	{
		return fileLength;
	}

	@Override
	public boolean equals(Object obj)
	{
		if (obj == null)
		{
			return false;
		}
		if (getClass() != obj.getClass())
		{
			return false;
		}
		final MultiFileInfo other = (MultiFileInfo) obj;
		if ((this.directory == null) ? (other.directory != null) : !this.directory.equals(other.directory))
		{
			return false;
		}
		if (this.files != other.files && (this.files == null || !this.files.equals(other.files)))
		{
			return false;
		}
		return true;
	}

	@Override
	public int hashCode()
	{
		int hash = 3;
		hash = 73 * hash + (this.directory != null ? this.directory.hashCode() : 0);
		return hash;
	}

	@Override
	public String toString()
	{
		String s = directory + " with:\n";
		for (SingleFileInfo f : files)
				s += "\t" + f.toString() + "\n";
		return s;
	}
	
	public ByteBuffer readPiece(int pieceIndex) throws Exception
	{			
		int cumulativeFileSizeTotal = 0;
		int pieceSize = getPieceLength();
		int limit = pieceIndex * pieceSize;
		String fileToRead = null;
		Queue<SingleFileInfo> fileQueue = new LinkedList<SingleFileInfo>(files);
		ByteBuffer b = ByteBuffer.allocate(pieceSize);
		
		
		while (fileQueue.peek() != null)
		{
			SingleFileInfo popped = fileQueue.poll();
			SingleFileInfo next = fileQueue.peek();
			
			cumulativeFileSizeTotal += popped.getFileLength();
			
			if (cumulativeFileSizeTotal == limit) //the next file(s) have the piece
			{
				//read the shit
				int bytesRead = 0;
				while (bytesRead != pieceSize)
				{
					next = fileQueue.poll();
					File f = new File(next.getName());
					if (f.length() > pieceSize - bytesRead) //rest of piece is in here
					{
						byte[] buffer = new byte[pieceSize - bytesRead];
						next.getInputStream().read(buffer, 0, buffer.length);
						bytesRead = pieceSize;
						b.put(buffer);
					}
					else //part of piece is in this file
					{
						byte[] buffer = new byte[(int)f.length()];
						next.getInputStream().read(buffer, 0, buffer.length);
						bytesRead += buffer.length;
						b.put(buffer);
					}
					
				}
				break;
			}
			else if (cumulativeFileSizeTotal + next.getFileLength() > limit) // the next file overlaps over the piece
			{
				//read the shit
				int previousLimit = (pieceIndex - 1) * pieceSize;
				int contentsInLastPiece = cumulativeFileSizeTotal - previousLimit;//this is the amount of the file in the previous piece
				
				next = fileQueue.poll();
				next.getInputStream().skip(contentsInLastPiece);
				
				int bytesRead = 0;
				byte[] buffer = new byte[pieceSize];
				
				bytesRead = next.getInputStream().read(buffer);
				b.put(buffer);
				
				while (bytesRead != pieceSize)
				{
					next = fileQueue.poll();
					File f = new File(next.getName());
					if (f.length() > pieceSize - bytesRead) //rest of piece is in here
					{
						buffer = new byte[pieceSize - bytesRead];
						next.getInputStream().read(buffer, 0, buffer.length);
						bytesRead = pieceSize;
						b.put(buffer);
					}
					else //part of piece is in this file
					{
						buffer = new byte[(int)f.length()];
						next.getInputStream().read(buffer, 0, buffer.length);
						bytesRead += buffer.length;
						b.put(buffer);
					}
					
				}
				
				break;
			}
		}
		
		return b;
	}
	
	public ByteBuffer readBlock(int pieceIndex, int blockOffset, int blockLength) throws Exception
	{
		int pieceSize = getPieceLength();
		ByteBuffer b = readPiece(pieceIndex);
		
		byte[] toWaste = new byte[blockOffset];
		byte[] useful = new byte[blockLength];
		b.get(toWaste);
		b.get(useful);
		
		return ByteBuffer.allocate(blockLength).put(useful);
		
	}
	
	public void writePiece(byte[] data, int pieceIndex) throws Exception
	{
		/*if (!(i instanceof MultiFileInfo))
			throw new Exception("i not of type " + i.getClass().getName());
		else if (data.length != pieceSize)
			throw new Exception("data not entire piece");
			
		int cumulativeFileSizeTotal = 0;
		int limit = pieceIndex * pieceSize;
		String fileToRead = null;
		Queue<SingleFileInfo> fileQueue = new LinkedList<SingleFileInfo>(files);
		ByteBuffer buffer = ByteBuffer.allocate(pieceSize);
		buffer.put(data);
		
		
		while (fileQueue.peek() != null)
		{
			SingleFileInfo popped = fileQueue.poll();
			SingleFileInfo next = fileQueue.peek();
			
			cumulativeFileSizeTotal += popped.getFileLength();
			
			if (cumulativeFileSizeTotal == limit) //the next file(s) have the piece
			{
				//read the shit
				int bytesWritten = 0;
				while (bytesWritten != pieceLength)
				{
					next = fileQueue.poll();
					File f = new File(next.getName());
					if (f.length() > pieceLength - bytesWritten) //rest of piece is in here
					{
						byte[] b = new byte[pieceLength - bytesWritten];
						buffer.get(b);
						next.getOutputStream().write(b, 0, b.length);
						bytesWritten = pieceLength;
					}
					else //part of piece is in this file
					{
						byte[] buffer = new byte[f.length()];
						b.get(buffer);
						next.getOutputStream().write(b, 0, b.length);
						bytesRead += b.length;
					}
					
				}
				break;
			}
			else if (cumulativeFileSizeTotal + next.getFileLength() > limit) // the next file overlaps over the piece
			{
				//read the shit
				int previousLimit = (pieceIndex - 1) * pieceSize;
				int contentsInLastPiece = cumulativeFileSizeTotal - previousLimit;//this is the amount of the file in the previous piece
				
				next = fileQueue.poll();
				
				int bytesWritten = 0;
				byte[] b = new byte[new File(next.getName()).length() - contentsInLastPiece];
				
				buffer.get(b);
				next.getOutputStream().write(b, contentsInLastPiece, b.length);
				
				bytesWritten = b.length;
				
				while (bytesRead != pieceLength)
				{
					next = fileQueue.poll();
					File f = new File(next.getName());
					if (f.length() > pieceLength - bytesWritten) //rest of piece is in here
					{
						
						next.getInputStream().read(buffer, 0, buffer.length);
						bytesRead = pieceLength;
						b.put(buffer);
					}
					else //part of piece is in this file
					{
						byte[] buffer = new byte[f.length()];
						next.getInputStream().read(buffer, 0, buffer.length);
						bytesRead += buffer.length;
						b.put(buffer);
					}
					
				}
				
				break;
			}
		}*/
		
		throw new Exception("unimplemented");
	}
	public void writeBlock(byte[] data, int pieceIndex, int blockOffset, int blockLength) throws Exception
	{
		throw new Exception("unimplemented");
	}
	
	public void finish() throws Exception
	{
		for (SingleFileInfo f : files)
		{
			f.finish();
		}
	}
}
