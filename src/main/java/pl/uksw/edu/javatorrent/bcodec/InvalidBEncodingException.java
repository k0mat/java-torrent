package pl.uksw.edu.javatorrent.bcodec;

/**
 * Created by PiotrBukowski on 2016-12-29
 */
import java.io.IOException;

public class InvalidBEncodingException extends IOException {

    public static final long serialVersionUID = -1;

    public InvalidBEncodingException(String message) {
        super(message);
    }
}
