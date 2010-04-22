package bitraptor;

import java.security.MessageDigest;
import java.util.*;

/**
 * Piece is a chunk of data that is divided into blocks
 */
public class Piece
{
	private byte[] piece;
	private int pieceIndex;
	private int pieceLength;
	private BitSet bytesReceived;
	private boolean isFinished;

	/**
	 * Creates a new Piece
	 * @param pieceIndex index of the piece (just for reference)
	 * @param pieceLength size of the piece in bytes
	 */
	public Piece(int pieceIndex, int pieceLength)
	{
		this.pieceIndex = pieceIndex;
		this.pieceLength = pieceLength;
		piece = new byte[pieceLength];
		bytesReceived = new BitSet(pieceLength);
		isFinished = false;
	}

	/**
		Writes the data to the piece.
		
		Throws an unchecked ArrayIndexOutOfBoundsException if blockOffset is greater than
		piece size or if data.length + blockOffset is great than piece size
		
		@param blockOffset  offset within the piece
		@param block  block to write
		@return true if piece is finished
	*/
	public boolean writeBlock(int blockOffset, byte[] block)
	{	
		//End game mode might see multiple of the same blocks written, so ignore it
		if (isFinished)
		{
			return false;
		}
		
		//Writing the block of data to the piece
		for (int c = 0; c < block.length; c++)
		{
			piece[blockOffset + c] = block[c];
		}
		
		bytesReceived.set(blockOffset, blockOffset + block.length);
		
		//Checking to see if the piece is now complete
		if (bytesReceived.cardinality() == pieceLength)
		{
			isFinished = true;
		}
		
		return isFinished;
	}

	/**
	 * Returns the entire piece
	 * @return an array of pieceSize bytes
	 */
	public byte[] getBytes()
	{
		return piece;
	}
	
	/**
	 * The index of the piece that was used to create this object
	 * @return the pieceIndex
	 */
	public int getPieceIndex()
	{
		return pieceIndex;
	}
	
	/**
	 * The length of the piece in bytes
	 * @return the pieceLength
	 */
	public int getPieceLength()
	{
		return pieceLength;
	}
	
	/**
	 * The SHA-1 hash of the piece
	 * @return an array of 20 bytes
	 * @throws Exception if some exception was thrown in SHA-1 computation
	 */
	public byte[] hash() throws Exception
	{
		try
		{
			return MessageDigest.getInstance("SHA-1").digest(piece);
		}
		catch (Exception e)
		{
			throw e;
		}
	}
}