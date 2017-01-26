package pl.uksw.edu.javatorrent.bittorrent.bencoder.datatypes;

/**
 * Created by mateusz on 2016-10-29.
 */
public class BencoderString extends BencoderObject<String> {
    @Override
    public void parse(String toParse) throws IllegalArgumentException {
        long stringLenght = 0;
        String[] split = toParse.split(":");
        try {
            stringLenght = Long.valueOf(split[0]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid bencoded string lenght: " + split[0]);
        }
        int separatorIndex = toParse.indexOf(':');
        if (stringLenght > toParse.length() - separatorIndex) {
            throw new IllegalArgumentException("Invalid bencoded string: " + toParse);
        }
        lenght = stringLenght + 1 + split[0].length();
        value = toParse.substring(separatorIndex + 1, (int) (separatorIndex + stringLenght) + 1);
    }

}
