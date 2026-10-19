package com.stagelog.photocard.domain;

import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.BadRequestException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * 네컷 프레임 SVG 의 최소 검증. 관리자만 올리지만 파일이 CDN 에서 image/svg+xml 로 그대로 서빙되므로,
 * 주소를 직접 열었을 때 실행될 수 있는 것(스크립트·이벤트 속성·javascript: 링크·HTML 삽입)과 XML 엔티티 공격을 막는다.
 * 실패는 모두 400 FRAME_SVG_INVALID.
 * 한계: 허용 목록 방식의 완전한 SVG 정화(sanitizer)가 아니다. CSS 의 외부 url() 로드 같은 비실행성 외부 참조는 거르지 않는다.
 */
public final class SvgValidator {

    public static final long MAX_BYTES = 2L * 1024 * 1024;

    private static final String SVG_NS = "http://www.w3.org/2000/svg";
    private static final String XLINK_NS = "http://www.w3.org/1999/xlink";
    private static final Set<String> FORBIDDEN_ELEMENTS = Set.of("script", "foreignobject", "iframe", "embed", "object");

    private SvgValidator() {
    }

    public static void validate(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw invalid("프레임 SVG 는 비어 있지 않은 2MB 이하 파일이어야 합니다.");
        }
        Element root = parse(bytes).getDocumentElement();
        if (!"svg".equals(root.getLocalName()) || !SVG_NS.equals(root.getNamespaceURI())) {
            throw invalid("루트가 SVG 네임스페이스의 <svg> 여야 합니다.");
        }
        validateViewBox(root.getAttribute("viewBox"));
        walk(root);
    }

    // DOCTYPE 자체를 금지해 외부 엔티티(XXE)·엔티티 폭탄을 막는다
    private static org.w3c.dom.Document parse(byte[] bytes) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            // 기본 오류 처리기는 표준 에러에 찍는다. 치명 오류만 예외로 받고 나머지는 무시한다
            builder.setErrorHandler(new DefaultHandler());
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML 파서 보안 설정을 적용할 수 없습니다", e);
        } catch (SAXException | IOException e) {
            throw invalid("올바른 XML 이 아니거나 DOCTYPE 이 들어 있습니다.");
        }
    }

    // 칸 좌표가 1080×3240 기준이라 viewBox 도 1:3 이어야 칸과 구멍이 맞는다 (앱은 viewBox 로 크기를 잡는다)
    private static void validateViewBox(String viewBox) {
        String[] parts = viewBox.trim().split("[\\s,]+");
        if (parts.length != 4) {
            throw invalid("viewBox 가 필요합니다.");
        }
        double width;
        double height;
        try {
            width = Double.parseDouble(parts[2]);
            height = Double.parseDouble(parts[3]);
        } catch (NumberFormatException e) {
            throw invalid("viewBox 가 숫자 네 개가 아닙니다.");
        }
        if (width <= 0 || height <= 0 || Math.abs(height - 3 * width) > height * 0.005) {
            throw invalid("viewBox 비율이 1:3 이어야 합니다.");
        }
    }

    private static void walk(Element element) {
        String name = element.getLocalName() == null ? element.getTagName() : element.getLocalName();
        if (FORBIDDEN_ELEMENTS.contains(name.toLowerCase(Locale.ROOT))) {
            throw invalid("<" + name + "> 요소는 쓸 수 없습니다.");
        }
        NamedNodeMap attrs = element.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            checkAttribute((Attr) attrs.item(i));
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element childElement) {
                walk(childElement);
            } else if ("style".equalsIgnoreCase(name) && (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE)) {
                String css = squash(child.getNodeValue());
                if (css.contains("@import") || css.contains("javascript:")) {
                    throw invalid("<style> 에 @import 나 javascript: 를 쓸 수 없습니다.");
                }
            }
        }
    }

    private static void checkAttribute(Attr attr) {
        String name = (attr.getLocalName() == null ? attr.getName() : attr.getLocalName()).toLowerCase(Locale.ROOT);
        String value = squash(attr.getValue());
        // 1. onload·onclick 같은 이벤트 속성
        if (name.startsWith("on")) {
            throw invalid("이벤트 속성(" + attr.getName() + ")은 쓸 수 없습니다.");
        }
        // 2. 어떤 속성이든 javascript: 값 (animate·set 의 values/to 로 href 를 바꾸는 경로 포함)
        if (value.contains("javascript:") || value.contains("vbscript:")) {
            throw invalid("javascript: 값은 쓸 수 없습니다.");
        }
        // 3. 링크는 문서 안(#), 앱 상대 경로(/...), 이미지 data URI 만
        boolean isHref = "href".equals(name) && (attr.getNamespaceURI() == null || XLINK_NS.equals(attr.getNamespaceURI()));
        if (isHref && !(value.startsWith("#") || value.startsWith("data:image/") || (value.startsWith("/") && !value.startsWith("//")))) {
            throw invalid("href 는 #id, /경로, data:image/ 만 쓸 수 있습니다.");
        }
    }

    // 공백·제어 문자로 키워드를 쪼개는 우회를 막는다
    private static String squash(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if (!Character.isWhitespace(c) && !Character.isISOControl(c)) {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    private static BadRequestException invalid(String message) {
        return new BadRequestException(ErrorCode.FRAME_SVG_INVALID, message);
    }
}
