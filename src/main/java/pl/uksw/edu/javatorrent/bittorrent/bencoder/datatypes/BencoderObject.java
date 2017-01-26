package pl.uksw.edu.javatorrent.bittorrent.bencoder.datatypes;

/**
 * Created by mateusz on 2016-10-29.
 */
public abstract class BencoderObject<T> {
    protected T value;
    protected long lenght;

    public final T getValue() {
        return value;
    }


    /**
     * @param toParse object to be parsed
     * @return returns number of consumed chars
     * @throws IllegalArgumentException thrown when toParse cannot be parsed
     */
    public abstract void parse(String toParse) throws IllegalArgumentException;

    public final long getLenght() {
        return lenght;
    }

}

