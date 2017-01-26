package pl.uksw.edu.javatorrent.bittorrent.bencoder;

import pl.uksw.edu.javatorrent.bittorrent.bencoder.datatypes.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by mateusz on 2016-10-29.
 */
public class BencoderParser {
    public List<BencoderObject> parseAll(String bencodedString) throws IllegalArgumentException {
        String currentString = bencodedString;
        long currentLenght = 0;
        List<BencoderObject> bencoderObjects = new ArrayList<>();
        while (!currentString.isEmpty()) {
            BencoderObject object = parseFirst(currentString);
            bencoderObjects.add(object);
            currentLenght += object.getLenght();
            currentString = currentString.substring((int) currentLenght);
        }
        return bencoderObjects;
    }

    public BencoderObject parseFirst(String bencodedString) throws IllegalArgumentException {
        BencoderObject result = null;
        if (bencodedString.startsWith(BencoderDictionary.PREFIX)) {
            result = new BencoderDictionary();
        } else if (bencodedString.startsWith(BencoderList.PREFIX)) {
            result = new BencoderList();
        } else if (bencodedString.startsWith(BencoderInteger.PREFIX)) {
            result = new BencoderInteger();
        } else {
            result = new BencoderString();
        }

        result.parse(bencodedString);

        return result;
    }
}
