package pl.uksw.edu.javatorrent.bittorrent.metadata;

/**
 * Created by mateusz on 2016-12-11.
 */
public class BittorrentFile {
    private String name;
    private long size;

    public BittorrentFile(String name, long size) {
        this.name = name;
        this.size = size;
    }

    public String getName() {
        return name;
    }

    public long getSize() {
        return size;
    }
}
