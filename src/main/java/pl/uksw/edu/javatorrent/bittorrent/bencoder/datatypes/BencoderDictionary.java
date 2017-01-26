package pl.uksw.edu.javatorrent.bittorrent.bencoder.datatypes;

import pl.uksw.edu.javatorrent.bittorrent.bencoder.BencoderParser;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by mateusz on 2016-10-29.
 */
public class BencoderDictionary extends BencoderObject<Map<String, BencoderObject>> {
    public static final String PREFIX = "d";

    @Override
    public void parse(String toParse) throws IllegalArgumentException {
        if (!toParse.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Object is not a bencoded dictionary: " + toParse);
        }

        value = new HashMap<>();
        if (checkIfEmptyDictionary(toParse)) {
            lenght = 2;
            return;
        }

        BencoderParser parser = new BencoderParser();

        lenght = 1;
        char c = 'a';
        while (lenght != toParse.length()) {
            String currentString = toParse.substring((int) lenght, toParse.length());
            BencoderString key = getKey(currentString);
            BencoderObject object = parser.parseFirst(currentString.substring((int) key.getLenght()));
            value.put(key.getValue(), object);
            lenght += key.getLenght() + object.getLenght();
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

    private BencoderString getKey(String substring) throws IllegalArgumentException {
        BencoderString bencoderString = new BencoderString();
        bencoderString.parse(substring);
        return bencoderString;
    }

    private boolean checkIfEmptyDictionary(String toParse) {
        return toParse.startsWith("de");
    }

}
