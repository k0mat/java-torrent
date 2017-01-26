package pl.uksw.edu.javatorrent.bittorrent.metadata;


import pl.uksw.edu.javatorrent.bittorrent.bencoder.datatypes.BencoderDictionary;
import pl.uksw.edu.javatorrent.bittorrent.bencoder.datatypes.BencoderList;
import pl.uksw.edu.javatorrent.bittorrent.bencoder.datatypes.BencoderObject;
import pl.uksw.edu.javatorrent.bittorrent.bencoder.datatypes.BencoderString;
import pl.uksw.edu.javatorrent.exceptions.BencoderException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class BittorrentMetaData {
    private static final String TWENTY_DOTS = "....................";
    private static final String SPLIT_REGEX = "(?<=\\G" + TWENTY_DOTS + ")";
    private List<String> trackers;
    private String name;
    private List<BittorrentFile> files;
    private long pieceLenght;
    private List<String> pieces;

    public static BittorrentMetaData fromDictionary(Map<String, BencoderObject> dictionary) throws BencoderException {
        BittorrentMetaData metaData = new BittorrentMetaData();
        checkForRequiredFields(dictionary);

        metaData.trackers = new ArrayList<>();
        if (dictionary.containsKey(FIELD_NAMES.ANNOUNCE_LIST)) {
            metaData.trackers = getTrackerList((BencoderList) dictionary.get(FIELD_NAMES.ANNOUNCE_LIST));
        } else {
            metaData.trackers.add((String) dictionary.get(FIELD_NAMES.ANNOUNCE).getValue());
        }
        BencoderDictionary info = (BencoderDictionary) dictionary.get(FIELD_NAMES.INFO);

        parseInfo(info.getValue(), metaData);

        return metaData;
    }

    private static void parseInfo(Map<String, BencoderObject> info, BittorrentMetaData metaData) {
        metaData.pieceLenght = (Long) info.get(FIELD_NAMES.PIECE_LENGTH).getValue();
        metaData.name = (String) info.get(FIELD_NAMES.NAME).getValue();
        String pieces = (String) info.get(FIELD_NAMES.PIECES).getValue();
        String encodedPieces = new String(pieces.getBytes(StandardCharsets.US_ASCII), StandardCharsets.UTF_8);
        metaData.pieces = Arrays.asList(encodedPieces.split(SPLIT_REGEX));

        metaData.files = new ArrayList<>();
        if (!info.containsKey(FIELD_NAMES.FILES)) {
            metaData.files.add(new BittorrentFile(metaData.name, (Long) info.get(FIELD_NAMES.LENGTH).getValue()));
        } else {
            BencoderList files = (BencoderList) info.get(FIELD_NAMES.FILES);
            for (BencoderObject bencoderObject : files.getValue()) {
                BencoderDictionary dic = (BencoderDictionary) bencoderObject;
                Map<String, BencoderObject> dicMap = dic.getValue();
                metaData.files.add(new BittorrentFile(((ArrayList<BencoderString>) dicMap.get(FIELD_NAMES.PATH).getValue()).get(0).getValue(), (Long) dicMap.get(FIELD_NAMES.LENGTH).getValue()));
            }
        }
    }

    private static List<String> getTrackerList(BencoderList bencoderList) {
        List<String> result = new ArrayList<>();
        for (BencoderObject o : bencoderList.getValue()) {
            if (o instanceof BencoderList) {
                if (!((BencoderList) o).getValue().isEmpty()) {
                    result.add((String) ((BencoderList) o).getValue().get(0).getValue());
                }
            }
        }

        return result;
    }

    private static void checkForRequiredFields(Map<String, BencoderObject> dictionary) throws BencoderException {
        checkForField(dictionary, FIELD_NAMES.ANNOUNCE);
        checkForField(dictionary, FIELD_NAMES.INFO);
    }

    private static void checkForField(Map<String, BencoderObject> dictionary, String field) throws BencoderException {
        if (!(dictionary.containsKey(field))) {
            throw new BencoderException("Required field " + field + " not found in torrent file");
        }
    }

    public List<String> getTrackers() {
        return trackers;
    }

    public String getName() {
        return name;
    }

    public List<BittorrentFile> getFiles() {
        return files;
    }

    public long getPieceLenght() {
        return pieceLenght;
    }

    public List<String> getPieces() {
        return pieces;
    }

    @Override
    public String toString() {
        return "BittorrentMetaData{" +
                "trackers=" + trackers +
                ", name='" + name + '\'' +
                ", files=" + files +
                ", pieceLenght=" + pieceLenght +
                ", pieces=" + pieces +
                '}';
    }

    interface FIELD_NAMES {
        String INFO = "info";
        String ANNOUNCE = "announce";
        String ANNOUNCE_LIST = "announce-list";
        String PIECE_LENGTH = "piece length";
        String NAME = "name";
        String PIECES = "pieces";
        String FILES = "files";
        String LENGTH = "length";
        String PATH = "path";
    }
}

