//go:build rtmp

package main

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"math"
)

const (
	amf0Number      = 0x00
	amf0Boolean     = 0x01
	amf0String      = 0x02
	amf0Object      = 0x03
	amf0Null        = 0x05
	amf0Undefined   = 0x06
	amf0EcmaArray   = 0x08
	amf0LongString  = 0x0C
	amf0ObjectEnd   = 0x09
)

var errAMF0Unsupported = errors.New("unsupported AMF0 type")

type amf0Value struct {
	Type   byte
	Number float64
	String string
	Object map[string]amf0Value
}

func decodeAMF0(r io.Reader) ([]amf0Value, error) {
	var values []amf0Value
	for {
		v, err := decodeAMF0Value(r)
		if err != nil {
			if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
				break
			}
			return values, err
		}
		values = append(values, v)
	}
	return values, nil
}

func decodeAMF0Value(r io.Reader) (amf0Value, error) {
	var typeBuf [1]byte
	if _, err := io.ReadFull(r, typeBuf[:]); err != nil {
		return amf0Value{}, err
	}
	switch typeBuf[0] {
	case amf0Number:
		var buf [8]byte
		if _, err := io.ReadFull(r, buf[:]); err != nil {
			return amf0Value{}, err
		}
		return amf0Value{Type: amf0Number, Number: math.Float64frombits(binary.BigEndian.Uint64(buf[:]))}, nil

	case amf0Boolean:
		var buf [1]byte
		if _, err := io.ReadFull(r, buf[:]); err != nil {
			return amf0Value{}, err
		}
		return amf0Value{Type: amf0Boolean, Number: float64(buf[0])}, nil

	case amf0String:
		var lenBuf [2]byte
		if _, err := io.ReadFull(r, lenBuf[:]); err != nil {
			return amf0Value{}, err
		}
		length := int(binary.BigEndian.Uint16(lenBuf[:]))
		buf := make([]byte, length)
		if _, err := io.ReadFull(r, buf); err != nil {
			return amf0Value{}, err
		}
		return amf0Value{Type: amf0String, String: string(buf)}, nil

	case amf0LongString:
		var lenBuf [4]byte
		if _, err := io.ReadFull(r, lenBuf[:]); err != nil {
			return amf0Value{}, err
		}
		length := int(binary.BigEndian.Uint32(lenBuf[:]))
		buf := make([]byte, length)
		if _, err := io.ReadFull(r, buf); err != nil {
			return amf0Value{}, err
		}
		return amf0Value{Type: amf0String, String: string(buf)}, nil

	case amf0Object, amf0EcmaArray:
		if typeBuf[0] == amf0EcmaArray {
			var countBuf [4]byte
			if _, err := io.ReadFull(r, countBuf[:]); err != nil {
				return amf0Value{}, err
			}
		}
		obj := make(map[string]amf0Value)
		for {
			var keyLenBuf [2]byte
			if _, err := io.ReadFull(r, keyLenBuf[:]); err != nil {
				return amf0Value{}, err
			}
			keyLen := int(binary.BigEndian.Uint16(keyLenBuf[:]))
		if keyLen == 0 {
			var endType [1]byte
			if _, err := io.ReadFull(r, endType[:]); err != nil {
				return amf0Value{}, err
			}
			if endType[0] == amf0ObjectEnd {
				break
			}
			return amf0Value{}, fmt.Errorf("unexpected AMF0 object key type after zero-length key: 0x%02x", endType[0])
		}
			keyBuf := make([]byte, keyLen)
			if _, err := io.ReadFull(r, keyBuf); err != nil {
				return amf0Value{}, err
			}
			val, err := decodeAMF0Value(r)
			if err != nil {
				return amf0Value{}, err
			}
			obj[string(keyBuf)] = val
		}
		return amf0Value{Type: amf0Object, Object: obj}, nil

	case amf0Null, amf0Undefined:
		return amf0Value{Type: typeBuf[0]}, nil

	default:
		return amf0Value{}, fmt.Errorf("unsupported AMF0 type: 0x%02x", typeBuf[0])
	}
}

func encodeAMF0Values(values []interface{}) []byte {
	var buf []byte
	for _, v := range values {
		buf = append(buf, encodeAMF0Value(v)...)
	}
	return buf
}

func encodeAMF0Value(v interface{}) []byte {
	switch val := v.(type) {
	case nil:
		return []byte{amf0Null}
	case float64:
		buf := make([]byte, 9)
		buf[0] = amf0Number
		binary.BigEndian.PutUint64(buf[1:], math.Float64bits(val))
		return buf
	case int:
		return encodeAMF0Value(float64(val))
	case string:
		return encodeAMF0String(val)
	case map[string]interface{}:
		return encodeAMF0Object(val)
	case map[string]amf0Value:
		return encodeAMF0ObjectFromValues(val)
	default:
		return []byte{amf0Null}
	}
}

func encodeAMF0String(s string) []byte {
	b := []byte(s)
	if len(b) > 65535 {
		buf := make([]byte, 5+len(b))
		buf[0] = amf0LongString
		binary.BigEndian.PutUint32(buf[1:5], uint32(len(b)))
		copy(buf[5:], b)
		return buf
	}
	buf := make([]byte, 3+len(b))
	buf[0] = amf0String
	binary.BigEndian.PutUint16(buf[1:3], uint16(len(b)))
	copy(buf[3:], b)
	return buf
}

func encodeAMF0ObjectKey(s string) []byte {
	b := []byte(s)
	buf := make([]byte, 2+len(b))
	binary.BigEndian.PutUint16(buf[:2], uint16(len(b)))
	copy(buf[2:], b)
	return buf
}

func encodeAMF0Object(m map[string]interface{}) []byte {
	var buf []byte
	buf = append(buf, amf0Object)
	for k, v := range m {
		buf = append(buf, encodeAMF0ObjectKey(k)...)
		buf = append(buf, encodeAMF0Value(v)...)
	}
	buf = append(buf, 0, 0, amf0ObjectEnd)
	return buf
}

func encodeAMF0ObjectFromValues(m map[string]amf0Value) []byte {
	var buf []byte
	buf = append(buf, amf0Object)
	for k, v := range m {
		buf = append(buf, encodeAMF0ObjectKey(k)...)
		switch v.Type {
		case amf0Number:
			buf = append(buf, encodeAMF0Value(v.Number)...)
		case amf0String:
			buf = append(buf, encodeAMF0String(v.String)...)
		case amf0Null, amf0Undefined:
			buf = append(buf, v.Type)
		default:
			buf = append(buf, amf0Null)
		}
	}
	buf = append(buf, 0, 0, amf0ObjectEnd)
	return buf
}
