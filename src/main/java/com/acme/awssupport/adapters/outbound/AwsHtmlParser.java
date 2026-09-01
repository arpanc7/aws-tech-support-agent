package com.acme.awssupport.adapters.outbound;

import com.acme.awssupport.domain.Types.*;
import com.acme.awssupport.ports.DocumentParser;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.springframework.stereotype.Component;

/**
 * Extracts AWS documentation content while retaining headings, anchors, lists, tables, and code.
 *
 * <p>Rejects missing main content and conflicting canonical URLs. Normalizes prose whitespace and
 * Unicode while preserving code whitespace. Source text remains untrusted data even after
 * extraction; parsing is not a proof of factual correctness or prompt-injection resistance.
 */
@Component
public class AwsHtmlParser implements DocumentParser {
  @Override
  public ParsedDocument parse(byte[] html, String sourceUrl) {
    org.jsoup.nodes.Document document =
        Jsoup.parse(new String(html, StandardCharsets.UTF_8), sourceUrl);
    Element main = document.selectFirst("#main-content");
    if (main == null || main.selectFirst("h1") == null)
      throw new IllegalArgumentException("Missing AWS main content or title");
    Element canonical = document.selectFirst("link[rel=canonical]");
    if (canonical != null
        && !canonical.absUrl("href").isEmpty()
        && !canonical.absUrl("href").equals(sourceUrl))
      throw new IllegalArgumentException("Document canonical URL differs from manifest");
    main.select(
            "script,style,nav,noscript,.awsdocs-note--warning-unrelated,#page-toc-src,#inline-topiclist,.awsdocs-page-footer")
        .remove();
    String title = main.selectFirst("h1").text();
    List<Block> blocks = new ArrayList<>();
    String heading = title;
    String anchor = main.selectFirst("h1").id();
    for (Element element : main.select("h2,h3,h4,p,pre,ul,ol,table,div.awsdocs-note")) {
      if (insideSelectedParent(element, main)) continue;
      if (element.tagName().matches("h[234]")) {
        heading = title + " > " + element.text();
        anchor = element.id();
        continue;
      }
      addBlocks(element, heading, anchor, blocks, "");
    }
    if (blocks.stream().mapToInt(b -> b.text().length()).sum() < 150)
      throw new IllegalArgumentException("No substantive AWS documentation extracted");
    return new ParsedDocument(title, List.copyOf(blocks));
  }

  /**
   * Splits explicit list/table structures while repeating available lead-in text and table headers.
   */
  private static void addBlocks(
      Element element, String heading, String anchor, List<Block> blocks, String context) {
    if (element.tagName().equals("ul") || element.tagName().equals("ol")) {
      for (Element item : element.children())
        if (item.tagName().equals("li")) addBlocks(item, heading, anchor, blocks, context);
      return;
    }
    if (element.tagName().equals("table") && element.select("tr").size() > 1) {
      String header = render(element.select("tr").getFirst()).strip();
      for (Element row : element.select("tr").subList(1, element.select("tr").size())) {
        blocks.add(
            new Block(
                heading,
                anchor,
                (context.isEmpty() ? "" : context + "\n\n") + header + "\n" + render(row).strip(),
                row.selectFirst("pre") != null));
      }
      return;
    }
    String text =
        element.tagName().equals("pre") ? render(element).stripTrailing() : render(element).strip();
    if (text.isBlank()) return;
    // Split long list steps only at explicit child blocks, repeating their lead-in context.
    if (element.tagName().equals("li") && text.length() > 5000 && element.childrenSize() > 1) {
      String lead = element.ownText().strip();
      Element first = element.children().getFirst();
      if (first.tagName().equals("p")) lead = (lead + "\n" + render(first).strip()).strip();
      for (Element child : element.children()) {
        if (child == first && first.tagName().equals("p")) continue;
        addBlocks(child, heading, anchor, blocks, (context + "\n" + lead).strip());
      }
      return;
    }
    boolean code = element.tagName().equals("pre") || element.selectFirst("pre") != null;
    blocks.add(
        new Block(heading, anchor, (context.isEmpty() ? "" : context + "\n\n") + text, code));
  }

  /**
   * Avoids extracting nested content twice when its enclosing structural block is already selected.
   */
  private static boolean insideSelectedParent(Element element, Element main) {
    for (Element p = element.parent(); p != null && p != main; p = p.parent()) {
      if (Set.of("p", "pre", "ul", "ol", "table").contains(p.tagName())
          || p.hasClass("awsdocs-note")) return true;
    }
    return false;
  }

  /**
   * Normalizes prose but preserves code whitespace; structural separators retain list/table
   * readability.
   */
  private static String render(Node node) {
    if (node instanceof TextNode text)
      return Normalizer.normalize(
              text.getWholeText().replace("\r\n", "\n").replace('\r', '\n'), Normalizer.Form.NFC)
          .replaceAll("[\\t\\n\\r ]+", " ");
    if (!(node instanceof Element element)) return "";
    if (element.tagName().equals("pre") || element.tagName().equals("code"))
      return element.wholeText().replace("\r\n", "\n").replace('\r', '\n');
    StringBuilder result = new StringBuilder();
    for (Node child : element.childNodes()) result.append(render(child));
    return switch (element.tagName()) {
      case "br" -> "\n";
      case "li" -> "\n- " + result.toString().strip() + "\n";
      case "td", "th" -> result.toString().strip() + " | ";
      case "tr", "p", "div" -> result.toString().strip() + "\n\n";
      default -> result.toString();
    };
  }
}
