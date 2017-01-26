package pl.uksw.edu.javatorrent.bcodec;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BEValue {

    private final Object value;

    public BEValue(byte[] value) {
        this.value = value;
    }

    public BEValue(String value) throws UnsupportedEncodingException {
        this.value = value.getBytes("UTF-8");
    }

    public BEValue(String value, String enc)
            throws UnsupportedEncodingException {
        this.value = value.getBytes(enc);
    }

    public BEValue(int value) {
        this.value = new Integer(value);
    }

    public BEValue(long value) {
        this.value = new Long(value);
    }

    public BEValue(Number value) {
        this.value = value;
    }

    public BEValue(List<BEValue> value) {
        this.value = value;
    }

    public BEValue(Map<String, BEValue> value) {
        this.value = value;
    }

    public Object getValue() {
        return this.value;
    }

    public String getString() throws InvalidBEncodingException {
        return this.getString("UTF-8");
    }

    public String getString(String encoding) throws InvalidBEncodingException {
        try {
            return new String(this.getBytes(), encoding);
        } catch (ClassCastException cce) {
            throw new InvalidBEncodingException(cce.toString());
        } catch (UnsupportedEncodingException uee) {
            throw new InternalError(uee.toString());
        }
    }

    public byte[] getBytes() throws InvalidBEncodingException {
        try {
            return (byte[])this.value;
        } catch (ClassCastException cce) {
            throw new InvalidBEncodingException(cce.toString());
        }
    }

    public Number getNumber() throws InvalidBEncodingException {
        try {
            return (Number)this.value;
        } catch (ClassCastException cce) {
            throw new InvalidBEncodingException(cce.toString());
        }
    }
    public short getShort() throws InvalidBEncodingException {
        return this.getNumber().shortValue();
    }

    public int getInt() throws InvalidBEncodingException {
        return this.getNumber().intValue();
    }


    public long getLong() throws InvalidBEncodingException {
        return this.getNumber().longValue();
    }

    @SuppressWarnings("unchecked")
    public List<BEValue> getList() throws InvalidBEncodingException {
        if (this.value instanceof ArrayList) {
            return (ArrayList<BEValue>)this.value;
        } else {
            throw new InvalidBEncodingException("Excepted List<BEvalue> !");
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, BEValue> getMap() throws InvalidBEncodingException {
        if (this.value instanceof HashMap) {
            return (Map<String, BEValue>)this.value;
        } else {
            throw new InvalidBEncodingException("Expected Map<String, BEValue> !");
        }
    }
}
