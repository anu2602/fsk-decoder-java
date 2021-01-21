
# FSK Decoder in Java

This is attempt to implement FSK Decoder in Java. Works in common use-cases. Extensive testing has not been done so may have some bugs. I invite community members to contribute unit tests and help make this more robust.   

The implementation was mainly intended to be used inside Android application, but can be used in any project that wants to use FSK decoder in JAVA.

**FSK Decoder** implementation provides method to convert data encoded in raw, uncompressed audio using FSK Decoder.
`decode` method takes raw audio data as PCM File/Stream and returns decoded data in bytes.

### Using FSK Decoder:

```
 FskDecoder fskDecoder = new FskDecoder();
 File pcmFile = new File("/path/to/file.pcm");
 decodedData = fskDecoder.decode(pcmFile);
 ```

## License

See the [LICENSE](LICENSE.md) file for license rights and limitations (MIT).