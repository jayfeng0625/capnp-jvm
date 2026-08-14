// Copyright (c) 2026 Jay Feng
// Licensed under the MIT License:
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.

package org.capnproto;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TextTest {

  // 1-, 2-, 3-, and 4-byte UTF-8 sequences.
  private static final String UNICODE = "héllo 世界 🚀";

  /** Independent oracle: byte-level search over plain arrays. */
  private static int byteIndexOf(byte[] haystack, byte[] needle) {
    if (needle.length == 0) return 0;
    for (int i = 0; i + needle.length <= haystack.length; ++i) {
      if (Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) {
        return i;
      }
    }
    return -1;
  }

  private static Text.Reader heapReader(String value) {
    return new Text.Reader(value);
  }

  private static Text.Reader directReader(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    ByteBuffer direct = ByteBuffer.allocateDirect(bytes.length);
    direct.put(bytes);
    direct.rewind();
    // Reader(ByteBuffer, offset, size) takes the offset in words.
    return new Text.Reader(direct, 0, bytes.length);
  }

  @Test
  public void testToStringHeapAscii() {
    assertEquals("hello world", heapReader("hello world").toString());
    assertEquals("", new Text.Reader().toString());
    assertEquals("", heapReader("").toString());
  }

  @Test
  public void testToStringHeapUnicode() {
    assertEquals(UNICODE, heapReader(UNICODE).toString());
  }

  @Test
  public void testToStringDirectBuffer() {
    assertEquals("hello world", directReader("hello world").toString());
    assertEquals(UNICODE, directReader(UNICODE).toString());
  }

  @Test
  public void testToStringReadOnlyHeapBuffer() {
    // hasArray() is false for read-only heap buffers: exercises the copy path.
    byte[] bytes = UNICODE.getBytes(StandardCharsets.UTF_8);
    Text.Reader reader = new Text.Reader(ByteBuffer.wrap(bytes).asReadOnlyBuffer(), 0, bytes.length);
    assertEquals(UNICODE, reader.toString());
  }

  @Test
  public void testToStringRespectsArrayOffset() {
    // A sliced buffer has a nonzero arrayOffset; the single-pass decode must
    // honor it or it would read the wrong region of the backing array.
    byte[] text = "abcdef".getBytes(StandardCharsets.UTF_8);
    byte[] backing = new byte[8 + text.length];
    System.arraycopy(text, 0, backing, 8, text.length);
    ByteBuffer sliced = ByteBuffer.wrap(backing, 8, text.length).slice();
    assertTrue(sliced.arrayOffset() != 0);

    Text.Reader reader = new Text.Reader(sliced, 0, text.length);
    assertEquals("abcdef", reader.toString());
  }

  @Test
  public void testToStringRespectsWordOffset() {
    // Reader(ByteBuffer, offset, size) takes a word offset: text at word 1.
    byte[] text = "wordy".getBytes(StandardCharsets.UTF_8);
    ByteBuffer buffer = ByteBuffer.allocate(16);
    buffer.put((byte) 'X'); // junk in word 0
    buffer.position(8);
    buffer.put(text);
    buffer.rewind();

    Text.Reader reader = new Text.Reader(buffer, 1, text.length);
    assertEquals("wordy", reader.toString());
  }

  @Test
  public void testReaderAsByteBufferIsAZeroCopyReadOnlyView() {
    byte[] bytes = "abc".getBytes(StandardCharsets.UTF_8);
    ByteBuffer backing = ByteBuffer.wrap(bytes);
    Text.Reader reader = new Text.Reader(backing, 0, bytes.length);

    ByteBuffer view = reader.asByteBuffer();
    assertTrue(view.isReadOnly());
    assertEquals(0, view.position());
    assertEquals(3, view.remaining());
    assertEquals('a', view.get(0));

    // Zero-copy: a write to the backing storage is visible through the view.
    bytes[0] = 'z';
    assertEquals('z', view.get(0));

    // The view is detached from the reader's buffer state...
    view.position(2);
    assertEquals(0, backing.position());
    // ...and cannot write back.
    assertThrows(ReadOnlyBufferException.class, () -> view.put(0, (byte) 'q'));
  }

  @Test
  public void testBuilderAsByteBufferWritesThrough() {
    byte[] bytes = "abc".getBytes(StandardCharsets.UTF_8);
    Text.Builder builder = new Text.Builder(ByteBuffer.wrap(bytes), 0, bytes.length);

    builder.asByteBuffer().put(0, (byte) 'x');
    assertEquals("xbc", builder.toString());
  }

  @Test
  public void testIndexOfAndContains() {
    String text = "the quick brown fox";
    Text.Reader reader = heapReader(text);

    assertEquals(0, reader.indexOf("the"));
    assertEquals(4, reader.indexOf("quick"));
    assertEquals(16, reader.indexOf("fox"));       // at the very end
    assertEquals(0, reader.indexOf(""));           // String.indexOf("") convention
    assertEquals(0, reader.indexOf(text));         // needle == whole text
    assertEquals(-1, reader.indexOf(text + "!"));  // needle longer than text
    assertEquals(-1, reader.indexOf("lazy"));

    assertTrue(reader.contains("quick"));
    assertTrue(reader.contains(new StringBuilder("brown"))); // any CharSequence
    assertFalse(reader.contains("lazy"));

    // Repeated-prefix pattern: naive scan must restart correctly.
    assertEquals(1, heapReader("aaab").indexOf("aab"));
  }

  @Test
  public void testIndexOfUnicodeReturnsByteOffsets() {
    byte[] textBytes = UNICODE.getBytes(StandardCharsets.UTF_8);
    Text.Reader reader = heapReader(UNICODE);

    for (String needle : new String[]{"héllo", "世界", "🚀", "llo 世"}) {
      byte[] needleBytes = needle.getBytes(StandardCharsets.UTF_8);
      assertEquals(byteIndexOf(textBytes, needleBytes), reader.indexOf(needle), needle);
      assertEquals(byteIndexOf(textBytes, needleBytes), reader.indexOf(needleBytes), needle);
      assertTrue(reader.contains(needle), needle);
    }
    assertFalse(reader.contains("世X"));
  }

  @Test
  public void testIndexOfIsConfinedToTheTextWindow() {
    // The buffer holds "cat" both before the text window and straddling its
    // end; neither may match. Builder takes a byte offset, so windows can be
    // byte-granular.
    byte[] backing = "catXYZdogcat".getBytes(StandardCharsets.UTF_8);
    Text.Builder window = new Text.Builder(ByteBuffer.wrap(backing), 6, 4); // "dogc"

    assertEquals(-1, window.indexOf("cat"));  // before the window and straddling its end
    assertEquals(0, window.indexOf("dog"));
    assertEquals("dogc", window.toString());
  }

  @Test
  public void testIndexOfOnDirectBuffer() {
    Text.Reader reader = directReader("the quick brown fox");
    assertEquals(4, reader.indexOf("quick"));
    assertEquals(-1, reader.indexOf("lazy"));
  }

  @Test
  public void testContentEquals() {
    Text.Reader reader = heapReader("hello");

    assertTrue(reader.contentEquals("hello"));
    assertTrue(reader.contentEquals(new StringBuilder("hello")));
    assertFalse(reader.contentEquals("hellx"));
    assertFalse(reader.contentEquals("hell"));    // shorter
    assertFalse(reader.contentEquals("hello!"));  // longer
    assertFalse(reader.contentEquals(""));

    assertTrue(heapReader("").contentEquals(""));
    assertFalse(heapReader("").contentEquals("x"));
  }

  @Test
  public void testContentEqualsUnicode() {
    Text.Reader reader = heapReader(UNICODE);

    assertTrue(reader.contentEquals(UNICODE));
    assertFalse(reader.contentEquals(UNICODE + "x"));
    assertFalse(reader.contentEquals("héllo 世畍 🚀")); // same byte length, one char off
    assertFalse(reader.contentEquals("hello 世界 🚀"));      // differs in the ASCII prefix

    // ASCII text against a non-ASCII operand of the same char length.
    assertFalse(heapReader("he").contentEquals("hé"));

    assertTrue(directReader(UNICODE).contentEquals(UNICODE));
  }

  @Test
  public void testContentEqualsIsConfinedToTheTextWindow() {
    byte[] backing = "XXhelloYY".getBytes(StandardCharsets.UTF_8);
    Text.Builder window = new Text.Builder(ByteBuffer.wrap(backing), 2, 5);

    assertTrue(window.contentEquals("hello"));
    assertFalse(window.contentEquals("XXhello"));
    assertFalse(window.contentEquals("helloYY"));
    assertEquals(5, window.size());
  }

  @Test
  public void testRoundTripThroughMessage() {
    MessageBuilder message = new MessageBuilder();
    message.setRoot(Text.factory, new Text.Reader(UNICODE));

    Text.Builder root = message.getRoot(Text.factory);
    assertEquals(UNICODE, root.toString());
    assertTrue(root.contentEquals(UNICODE));
    assertTrue(root.contains("世界"));
    assertEquals(UNICODE.getBytes(StandardCharsets.UTF_8).length, root.size());
  }
}
