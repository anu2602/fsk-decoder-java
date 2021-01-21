package org.jfsk;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 
 * @author Anuradha Chowdhary
 * @author info@achowdhary.com
 *
 * <b>FSK Decoder</b> implementation in Java. This implementation provides method to convert data 
 * encoded in raw, uncompressed audio using FSK Decoder.
 * 
 * Refer to two methods that take raw audio data as PCM File or PCM Input Stream and returns 
 * decoded data in bytes.
 * 
 * {@link #decode(File)}
 * {@link #decode(InputStream)}
 * 
 * Here is example use:
 *<pre> {@code
 *  FskDecoder fskDecoder = new FskDecoder();
 *  File pcmFile = new File("/path/to/file.pcm");
 *  decodedData = fskDecoder.decode(pcmFile);
 * }</pre>
 */

public class FskDecoder {
	private static final Logger logger = Logger.getLogger(FskDecoder.class.getName());
	
	private static final int  SELECTED_FSK = 4;
	private static final int MAX_DATA_SIZE=256;
	
	private static final double MATH_PI        = 3.14159265358979323846;	
	private static final int FSK_STATE_CHANSEIZE = 0;
	private static final int FSK_STATE_CARRIERSIG = 1;
	private static final int FSK_STATE_DATA = 2;
	private static final int FSK_STATE_SYNC = 3;
    
	private static final int SAMPLE_RATE = 8000;
	private static final short SYNC_SEQUENCE  = (short)0xAB4D; // 1010 1011 0100 1101
	
	static class FskModemDefinition {
		 int freqSpace;             // Frequency of the 0 bit      
		 int freqMark;              // Frequency of the 1 bit         
		 int baudRate;              // baud rate for the modem
		
		 public FskModemDefinition(int freqSpace, int freqMark, int baudRate) {
			this.freqMark = freqMark;
			this.freqSpace = freqSpace;
			this.baudRate = baudRate;
		}
	};
	
	/**
	 * Defines some standard modem definitions. If using a different 
	 * definition, add modem definition here.
	 */
	
	private static final FskModemDefinition FSK_MODEM_DEFINITIONS[] = 
		{
		 new FskModemDefinition(1700, 1300, 600),   // FSK_V23_FORWARD_MODE1 Maximum 600 bps for long haul         
		 new FskModemDefinition(2100, 1300, 1200),  // FSK_V23_FORWARD_MODE2 Standard 1200 bps V.23               
		 new FskModemDefinition(450, 390, 75),      // FSK_V23_BACKWARD 75 bps return path for V.23                   
		 new FskModemDefinition(2400, 1200, 500),   // FSK_BELL202  Bell 202 half-duplex 1200 bps                   
		 new FskModemDefinition(2000, 1000, 500 )   // FSK Custom_Example */   
		};
	
	public static char[] CHAR_MAP = {0,1,2,3,4,5,6,7,8,9,'A','B','C','D','E','F',};

	//Member variables
	private int tempIndex = 0;
	private int prevChar;
	private boolean skippingLeadingZeroes = true;
	private double normalizedValue;
	private int trailingZeroes = 0;
	private boolean running = true;
	private int handleDownsamplingCount = 1;
	private int handleCorrSize = SAMPLE_RATE / handleDownsamplingCount / FSK_MODEM_DEFINITIONS[SELECTED_FSK].freqMark;
	private double handleCorrelates[][] ; 
	private double handleBuffer[];
	private int handleRingStart = 0;
	private int handleState;
	private double handleCellPos;              // bit cell position
	private double handleCellAdj;
	private  boolean handlePreviousBit;        // previous bit (for detecting a transition to sync-up cell position) 
	private boolean handleCurrentBit;          // current bit 
	private boolean handleLastBit;
	private int handleConscutiveStateBits;     // number of bits in a row that matches the pattern for the current state 
	private int handleNibbleCount;
	private int handleNibble;
	private int handleCharCount;
	private int handleDataSize;
	private short handleSyncData;
	private int handleTempByte;
	
	private byte[] decodedBytes;
	private int [] outputBuffer = new int[MAX_DATA_SIZE];
	private int length;
	
	public FskDecoder() {
		double phiMark = 2. * MATH_PI / ((double) SAMPLE_RATE / (double) handleDownsamplingCount / (double) FSK_MODEM_DEFINITIONS[SELECTED_FSK].freqMark);
		double phiSpace = 2. * MATH_PI / ((double) SAMPLE_RATE / (double) handleDownsamplingCount / (double) FSK_MODEM_DEFINITIONS[SELECTED_FSK].freqSpace);
		
		handleCorrelates = new double[4][handleCorrSize];
	     for (int i = 0; i < handleCorrSize; i++) {
	    	 handleCorrelates[0][i] = Math.sin(phiMark * (double) i);
	    	 handleCorrelates[1][i] = Math.cos(phiMark * (double) i);
	    	 handleCorrelates[2][i] = Math.sin(phiSpace * (double) i);
	    	 handleCorrelates[3][i] = Math.cos(phiSpace * (double) i);
	     }
   
	     handleBuffer = new double[handleCorrSize];
	     handleRingStart = 0;

	     handleCellPos = 0;
	     handleCellAdj = FSK_MODEM_DEFINITIONS[SELECTED_FSK].baudRate / (double) SAMPLE_RATE * (double) handleDownsamplingCount;
	}
	
	/**
	 * Decode the data encoded into given PCM file.
	 * 
	 * @param pcmFile FSK encoded data in PCM File.
	 * @return decoded data bytes
	 * @throws Exception
	 */
	public byte[] decode(File pcmFile) throws Exception{
		FileInputStream pcmReader =new FileInputStream(pcmFile);
		byte[] decodedData = decode(pcmReader);;
		pcmReader.close();
		return decodedData;
	}
	
	/**
	 * Decode the data encoded into given PCM reader.
	 * 
	 * @param pcmReader FSK encoded data reader.
	 * @return decoded data bytes
	 * @throws Exception
	 */
    public byte[] decode(InputStream pcmReader) throws Exception{
		int dataByte = pcmReader.read();
		while(dataByte != -1) {
			if (running) {
				short sample;
				if (tempIndex == 0) {
					prevChar = dataByte;
					tempIndex++;
					dataByte = pcmReader.read();
					continue;
				} else {
					sample = (short)((dataByte << 8) | prevChar);
					tempIndex = 0;
				}
				
				if (skippingLeadingZeroes) {
					if (sample != 0) skippingLeadingZeroes = false;
				}
				
				normalizedValue = (double) sample / 32768;
				if (!skippingLeadingZeroes) {
					if (sample == 0)trailingZeroes++;
					else trailingZeroes = 0;
					
					if (trailingZeroes == 1000) {
						logger.log(Level.SEVERE,"Existing Trailing Zeros detected");
						running = false;
						dataByte = pcmReader.read();
						continue;
					}
					
					int dataCount = dspFskSample(normalizedValue);
					if (dataCount != 0) break; 
				}
			}
			dataByte = pcmReader.read();
		}
		if(length>0 && length <=MAX_DATA_SIZE){
			ByteArrayOutputStream bout = new ByteArrayOutputStream();
			for(int i =0; i < length; i++) {
				bout.write(outputBuffer[i]);
			}
			decodedBytes = bout.toByteArray();
		}	
		return decodedBytes;
	}
    	
	private int dspFskSample(double normalizedSample) {
		double val = 234.234;
		double factors[] = new double[4];
		int i, j;
		handleBuffer[handleRingStart++] = normalizedSample;
		if (handleRingStart >= handleCorrSize) {
			handleRingStart = 0;
		}
		
		factors[0] = factors[1] = factors[2] = factors[3] = 0; 
																 
		j = handleRingStart;
		for (i = 0; i < handleCorrSize; i++) {
			if (j >= handleCorrSize) {
				j = 0;
			}
			val = handleBuffer[j];
			factors[0] += handleCorrelates[0][i] * val;
			factors[1] += handleCorrelates[1][i] * val;
			factors[2] += handleCorrelates[2][i] * val;
			factors[3] += handleCorrelates[3][i] * val;
			j++;
		}

		handlePreviousBit = handleCurrentBit;
		handleCurrentBit = (factors[0] * factors[0] + factors[1] * factors[1] > factors[2]* factors[2] + factors[3] * factors[3]);

		if (handlePreviousBit != handleCurrentBit) {
			handleCellPos = 0.5; 
		}
		handleCellPos += handleCellAdj; 

		if (handleCellPos > 1.0) {
			handleCellPos -= 1.0;

			switch (handleState) {
			case FSK_STATE_DATA: {
				int fourBnibble = 0;
				handleNibble = handleNibble | ((handleCurrentBit ? 0 : 1) & 0xff);
				handleNibbleCount++;
				if (handleNibbleCount > 5) {
					switch (handleNibble) {
						case 0x12: fourBnibble = 0x00;  break;
						case 0x13: fourBnibble = 0x01; 	break;
						case 0x14: fourBnibble = 0x02;  break;
						case 0x15: fourBnibble = 0x03;	break;
						case 0x16: fourBnibble = 0x04; 	break;
						case 0x19: fourBnibble = 0x05;	break;
						case 0x1A: fourBnibble = 0x06; 	break;
						case 0x23: fourBnibble = 0x07;  break;
						case 0x24: fourBnibble = 0x08;  break;
						case 0x25: fourBnibble = 0x09;  break;
						case 0x26: fourBnibble = 0x0A;  break;
						case 0x29: fourBnibble = 0x0B;  break;
						case 0x2A: fourBnibble = 0x0C; 	break;
						case 0x2B: fourBnibble = 0x0D; 	break;
						case 0x2C: fourBnibble = 0x0E;  break;
						case 0x2D: fourBnibble = 0x0F;  break;
						default: logger.log(Level.SEVERE,"Error : " +handleNibble);break;
				    }

					if (handleCharCount < 4) {
						if (((handleCharCount) & 0x1) == 0) {
							handleTempByte = ((fourBnibble << 4) & 0xF0);
						} else {
							handleTempByte = (handleTempByte | (fourBnibble & 0xF));
							outputBuffer[handleCharCount / 2] = handleTempByte;
						}
						if (handleCharCount == 3) {
							handleDataSize = ((outputBuffer[0] << 8) & 0xff00)| (outputBuffer[1] & 0x00ff);
							handleDataSize = handleDataSize * 2 + 4;
							logger.log(Level.FINE, "handleDataSize="+handleDataSize);
							if(handleDataSize!=292){
								handleDataSize = 288;
							}
							length = handleDataSize/2;
						}
					} else if (handleCharCount < handleDataSize) {
						if (((handleCharCount) & 0x1) == 0) {
							handleTempByte = ((fourBnibble << 4) & 0xF0);
						} else {
							handleTempByte = handleTempByte | (fourBnibble & 0xF);
							outputBuffer[handleCharCount / 2] = handleTempByte;
						}
					}

					if (handleCharCount > 4 && handleCharCount >= handleDataSize) {
						logger.log(Level.FINE,"Processing done");
						running = false;
						return handleDataSize / 2;
					}
					handleCharCount++;
					handleNibbleCount = 0;
					handleNibble = 0;
				} else {
					handleNibble = (handleNibble << 1);
				}

			}break;
			
			case FSK_STATE_CHANSEIZE:{
				if (handleLastBit != handleCurrentBit)handleConscutiveStateBits++;
				else handleConscutiveStateBits = 0;

				if (handleConscutiveStateBits > 30) {
					handleState = FSK_STATE_SYNC;
					handleConscutiveStateBits = 0;
				}
			}break;

			case FSK_STATE_SYNC:{
				handleSyncData = (short) (handleSyncData << 1);
				handleSyncData |= (handleCurrentBit ? 0 : 1);

				if (handleSyncData == SYNC_SEQUENCE) handleState = FSK_STATE_DATA;
			}break;

			case FSK_STATE_CARRIERSIG: { 
				if (handleCurrentBit) handleConscutiveStateBits++;
				else handleConscutiveStateBits = 0;

				if (handleConscutiveStateBits > 15) {
					handleState = FSK_STATE_DATA;
					handleConscutiveStateBits = 0;
				}
			}break;
			}
			handleLastBit = handleCurrentBit;
		}
		return 0;
	}	
}
