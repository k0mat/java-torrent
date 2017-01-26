package pl.uksw.edu.javatorrent.bittorrent.bencoder.datatypes;

import pl.uksw.edu.javatorrent.bittorrent.bencoder.BencoderParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by mateusz on 2016-10-29.
 */
public class BencoderList extends BencoderObject<List<BencoderObject>> {
    public static final String PREFIX = "l";

    @Override
    public void parse(String toParse) {
        if (!toParse.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Object is not a bencoded dictionary: " + toParse);
        }

        value = new ArrayList<>();
        if (checkIfEmptyList(toParse)) {
            lenght = 2;
            return;
        }

        BencoderParser parser = new BencoderParser();

        lenght = 1;
        char c = 'a';
        while (lenght != toParse.length()) {
            String currentString = toParse.substring((int) lenght, toParse.length());
            BencoderObject object = parser.parseFirst(currentString);
            value.add(object);
            lenght += object.getLenght();
            c = toParse.charAt((int) lenght);
            if (c == 'e') {
                break;
            }
        }
        if (c != 'e') {
            throw new IllegalArgumentException("Object is not a valid bencoded map: " + toParse);
        }
        lenght += 1;
    }

    private boolean checkIfEmptyList(String toParse) {
        return toParse.startsWith("le");
    }
}
