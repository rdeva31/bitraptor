package bitraptor;
import java.util.ArrayList;
import java.util.List;

/**
 *
 * @author rdeva
 */
public class MultiFileInfo extends Info
{
	private String directory = null; //directory name to store all files in
	private List<SingleFileInfo> files = null;

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
	}

	public String getDirectory() {
		return directory;
	}

	public void setDirectory(String directory) {
		this.directory = directory;
	}

	/**
	 * Returns the files with in this directory.  Note that to find the exact path
	 * of a given file f, do this:
	 * SingleFileInfo f = getFiles().get(0);
	 * System.out.println(getDirectory() + "/" + f.getName() + " is the location of file");
	 * @return all files relative to the current directory
	 */
	public List<SingleFileInfo> getFiles() {
		return files;
	}

	public void setFiles(List<SingleFileInfo> files) {
		this.files = files;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		final MultiFileInfo other = (MultiFileInfo) obj;
		if ((this.directory == null) ? (other.directory != null) : !this.directory.equals(other.directory)) {
			return false;
		}
		if (this.files != other.files && (this.files == null || !this.files.equals(other.files))) {
			return false;
		}
		return true;
	}

	@Override
	public int hashCode() {
		int hash = 3;
		hash = 73 * hash + (this.directory != null ? this.directory.hashCode() : 0);
		return hash;
	}

	@Override
	public String toString()
	{
		StringBuffer s = new StringBuffer(directory + " with: \n");
		for (SingleFileInfo f : files)
				s.append("\t" + f.toString() + " \n");
		return s.toString();
	}
}