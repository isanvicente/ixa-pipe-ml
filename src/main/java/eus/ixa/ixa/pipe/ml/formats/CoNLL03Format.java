/*
 *  Copyright 2015 Rodrigo Agerri

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package eus.ixa.ixa.pipe.ml.formats;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

import eus.ixa.ixa.pipe.ml.sequence.SequenceLabelSample;
import eus.ixa.ixa.pipe.ml.utils.Span;
import opennlp.tools.util.InputStreamFactory;
import opennlp.tools.util.InvalidFormatException;
import opennlp.tools.util.ObjectStream;
import opennlp.tools.util.PlainTextByLineStream;
import opennlp.tools.util.StringUtil;

/**
 * 2 fields CoNLL 2003 tabulated format: word\tabclass\n I- start chunk B- begin
 * chunk when next to same class entity O- outside chunk
 *
 * @author ragerri
 * @version 2015-02-24
 *
 */
public class CoNLL03Format implements ObjectStream<SequenceLabelSample> {

  /**
   * The doc mark present in CoNLL 2003 datasets.
   */
  public static final String DOCSTART = "-DOCSTART-";
  /**
   * The line stream.
   */
  private final ObjectStream<String> lineStream;
  /**
   * Clear adaptive features.
   */
  private final String clearFeatures;

  /**
   * Construct a CoNLL03Format formatter.
   * 
   * @param resetFeatures
   *          clear adaptive features
   * @param lineStream
   *          the stream
   */
  public CoNLL03Format(final String resetFeatures,
      final ObjectStream<String> lineStream) {
    this.clearFeatures = resetFeatures;
    this.lineStream = lineStream;
  }

  /**
   * Construct a CoNLL03 formatter.
   * 
   * @param resetFeatures
   *          the features to be reset
   * @param in
   *          inputstream factory
   * @throws IOException
   *           the io exception
   */
  public CoNLL03Format(final String resetFeatures, final InputStreamFactory in)
      throws IOException {

    this.clearFeatures = resetFeatures;
    try {
      this.lineStream = new PlainTextByLineStream(in, "UTF-8");
      System.setOut(new PrintStream(System.out, true, "UTF-8"));
    } catch (final UnsupportedEncodingException e) {
      // UTF-8 is available on all JVMs, will never happen
      throw new IllegalStateException(e);
    }
  }

  @Override
  public SequenceLabelSample read() throws IOException {

    final List<String> tokens = new ArrayList<String>();
    final List<String> seqTypes = new ArrayList<String>();
    boolean isClearAdaptiveData = false;

    // Empty line indicates end of sentence
    String line;
    while ((line = this.lineStream.read()) != null
        && !StringUtil.isEmpty(line)) {
      // clear adaptive data if document mark appears following
      // CoNLL03 conventions
      if (this.clearFeatures.equalsIgnoreCase("docstart")
          && line.startsWith("-DOCSTART-")) {
        isClearAdaptiveData = true;
        final String emptyLine = this.lineStream.read();
        if (!StringUtil.isEmpty(emptyLine)) {
          throw new IOException(
              "Empty line after -DOCSTART- not empty: '" + emptyLine + "'!");
        }
        continue;
      }
      final String fields[] = line.split("\t");
      if (fields.length == 2) {
        tokens.add(fields[0]);
        seqTypes.add(fields[1]);
      } else {
        throw new IOException(
            "Expected two fields per line in training data, got "
                + fields.length + " for line '" + line + "'!");
      }
    }
    // if no -DOCSTART- mark, check if we need to clear features every sentence
    if (this.clearFeatures.equalsIgnoreCase("yes")) {
      isClearAdaptiveData = true;
    }

    if (tokens.size() > 0) {
      // convert sequence tags into spans
      final List<Span> sequences = new ArrayList<Span>();

      int beginIndex = -1;
      int endIndex = -1;
      for (int i = 0; i < seqTypes.size(); i++) {
        final String seqTag = seqTypes.get(i);
        if (seqTag.equals("O")) {
          // O means we don't have anything this round.
          if (beginIndex != -1) {
            sequences
                .add(extract(beginIndex, endIndex, seqTypes.get(beginIndex)));
            beginIndex = -1;
            endIndex = -1;
          }
        } else if (seqTag.startsWith("B-")) {
          // B- prefix means we have two same entities of the same class next to
          // each other
          if (beginIndex != -1) {
            sequences
                .add(extract(beginIndex, endIndex, seqTypes.get(beginIndex)));
          }
          beginIndex = i;
          endIndex = i + 1;
        } else if (seqTag.startsWith("I-")) {
          // I- starts or continues a current name entity
          if (beginIndex == -1) {
            beginIndex = i;
            endIndex = i + 1;
          } else if (!seqTag.endsWith(seqTypes.get(beginIndex).substring(1))) {
            // we have a new tag type following a tagged word series
            // also may not have the same I- starting the previous!
            sequences
                .add(extract(beginIndex, endIndex, seqTypes.get(beginIndex)));
            beginIndex = i;
            endIndex = i + 1;
          } else {
            endIndex++;
          }
        } else {
          throw new IOException("Invalid tag: " + seqTag);
        }
      }

      // if one span remains, create it here
      if (beginIndex != -1) {
        sequences.add(extract(beginIndex, endIndex, seqTypes.get(beginIndex)));
      }

      return new SequenceLabelSample(tokens.toArray(new String[tokens.size()]),
          sequences.toArray(new Span[sequences.size()]), isClearAdaptiveData);
    } else if (line != null) {
      // Just filter out empty events, if two lines in a row are empty
      return read();
    } else {
      // source stream is not returning anymore lines
      return null;
    }
  }

  static final Span extract(final int begin, final int end,
      final String beginTag) throws InvalidFormatException {

    final String type = beginTag.substring(2);
    return new Span(begin, end, type);
  }

  @Override
  public void reset() throws IOException, UnsupportedOperationException {
    this.lineStream.reset();
  }

  @Override
  public void close() throws IOException {
    this.lineStream.close();
  }

}
