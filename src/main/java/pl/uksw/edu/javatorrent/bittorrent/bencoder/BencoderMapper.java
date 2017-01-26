package pl.uksw.edu.javatorrent.bittorrent.bencoder;

import pl.uksw.edu.javatorrent.bittorrent.bencoder.datatypes.BencoderDictionary;
import pl.uksw.edu.javatorrent.bittorrent.bencoder.datatypes.BencoderInteger;
import pl.uksw.edu.javatorrent.bittorrent.bencoder.datatypes.BencoderObject;
import pl.uksw.edu.javatorrent.bittorrent.bencoder.datatypes.BencoderString;
import pl.uksw.edu.javatorrent.exceptions.BencoderMapperException;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Created by mateusz on 2016-10-29.
 */
public class BencoderMapper {
    private BencoderNamingPolicy namingPolicy;
    private BencoderParser parser = new BencoderParser();

    public BencoderMapper() {
        this(BencoderNamingPolicy.ANNOTATION_ONLY);
    }

    public BencoderMapper(BencoderNamingPolicy namingPolicy) {
        this.namingPolicy = namingPolicy;
    }

    public <T> T map(String fileData, Class<? extends T> mappedObject) throws BencoderMapperException {
        T result = getInstance(mappedObject);

        BencoderObject parsedData = parser.parseFirst(fileData);
        if (!(parsedData instanceof BencoderDictionary)) {
            throw new BencoderMapperException("Torrent data invalid");
        }

        BencoderDictionary dictionary = (BencoderDictionary) parsedData;
        Map<String, BencoderObject> map = dictionary.getValue();

        mapDictionary(result, map);
        return result;
    }

    private <T> void mapDictionary(T result, Map<String, BencoderObject> map) {
        for (Field field : result.getClass().getDeclaredFields()) {
            if (field.isAnnotationPresent(BencoderProperty.class)) {
                BencoderProperty annotation = field.getAnnotation(BencoderProperty.class);
                if (map.containsKey(annotation.value())) {
                    mapField(field, map.get(annotation.value()));
                }
            }
        }
    }

    private void mapField(Field field, BencoderObject bencoderObject) {
        if (bencoderObject instanceof BencoderString || bencoderObject instanceof BencoderInteger) {

        }
    }


    private <T> T getInstance(Class<? extends T> mappedObject) throws BencoderMapperException {
        T result;
        try {
            result = mappedObject.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            e.printStackTrace();
            throw new BencoderMapperException("Error creating instance");
        }
        return result;
    }
}
