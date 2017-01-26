package pl.uksw.edu.javatorrent.bittorrent.bencoder.datatypes;

/**
 * Created by mateusz on 2016-10-29.
 */
public class BencoderInteger extends BencoderObject<Long> {
    public static final String PREFIX = "i";

    @Override
    public void parse(String toParse) {
        int eIndex = toParse.indexOf('e');
        if (!toParse.startsWith(PREFIX) && eIndex != -1) {
            throw new IllegalArgumentException("Object is not a bencoded integer: " + toParse);
        }

        String intString = toParse.substring(1, eIndex);

        if (intString.isEmpty() || (intString.length() > 1 && intString.startsWith("0"))) {
            throw new IllegalArgumentException("Object is not a valid bencoded integer: " + intString);
        }

        try {
            value = Long.valueOf(intString);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Object is not a valid bencoded integer: " + intString);
        }
        lenght = intString.length() + 2;
    }
}
