package org.miaixz.maven.groom;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.bootstrap.DOMImplementationRegistry;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSOutput;
import org.w3c.dom.ls.LSSerializer;
import org.xml.sax.SAXException;

/**
 * Helper class to keep the comments how they have been in the original pom.xml While reading with
 * {@link org.apache.maven.model.io.xpp3.MavenXpp3Writer} the comments are not placed into the
 * {@link org.apache.maven.model.Model} and so {@link org.apache.maven.model.io.xpp3.MavenXpp3Writer} is not able to
 * re-write those comments.
 * <p>
 * Workaround (maybe until core is fixed) is to remember all the comments and restore them after MavenXpp3Writer has
 * created the new flattened pom.xml.
 * <p>
 * Current restriction on non-unique child nodes is that this class finds the node back due to the position in the file,
 * that may lead to mis-re-added comments e.g. on multiple added dependencies (but for e.g. resolveCiFriendliesOnly the
 * nodes keep stable)
 */
class KeepCommentsInPom {

    /**
     * Create an instance with collected current comments of the passed pom.xml file.
     *
     * @param aLog             the Maven logger.
     * @param aOriginalPomFile the original POM file to read comments from.
     * @return the comment keeper initialized with comments from the original POM.
     * @throws MojoExecutionException when the original POM cannot be parsed.
     */
    static KeepCommentsInPom create(Log aLog, File aOriginalPomFile) throws MojoExecutionException {
        KeepCommentsInPom tempKeepCommentsInPom = new KeepCommentsInPom();
        tempKeepCommentsInPom.setLog(aLog);
        tempKeepCommentsInPom.loadComments(aOriginalPomFile);
        return tempKeepCommentsInPom;
    }

    /**
     * Maven logger used for debug traversal output.
     */
    private Log log;

    /**
     * The unique path list for an original node; comments are stored via the referenced previous sibling.
     */
    private Map<String, Node> commentsPaths;

    /**
     * Creates an empty comment keeper.
     */
    KeepCommentsInPom() {
        super();
    }

    /**
     * Loads all current comments and text fragments from the original XML file.
     *
     * @param anOriginalPomFile the original POM file.
     * @throws MojoExecutionException when the original POM cannot be parsed.
     */
    private void loadComments(File anOriginalPomFile) throws MojoExecutionException {
        commentsPaths = new HashMap<>();
        DocumentBuilderFactory tempDBF = DocumentBuilderFactory.newInstance();
        DocumentBuilder tempDB;
        try {
            tempDB = tempDBF.newDocumentBuilder();
            Document tempPom = tempDB.parse(anOriginalPomFile);
            Node tempNode = tempPom.getDocumentElement();
            walkOverNodes(tempNode, ".", (node, nodePath) -> {
                // collectNodesByPathNames
                commentsPaths.put(nodePath, node);
            });
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new MojoExecutionException("Cannot load comments from " + anOriginalPomFile, e);
        }
    }

    /**
     * Walk over the pom hierarchy of the Document.
     *
     * @param aNode       the current node.
     * @param aParentPath the unique path in the parent.
     * @param aConsumer   the function to call with each collected node.
     */
    private void walkOverNodes(Node aNode, String aParentPath, BiConsumer<Node, String> aConsumer) {
        String tempNodeName = aNode.getNodeName();
        if (log.isDebugEnabled()) {
            log.debug("walkOverNodes: aParentPath=" + aParentPath + " tempNodeName=" + tempNodeName);
        }
        String tempNodePath = aParentPath + "\t" + tempNodeName;
        aConsumer.accept(aNode, tempNodePath);
        NodeList tempChilds = aNode.getChildNodes();
        // Copy the childs as aConsumer may change the node sequence (add a comment)
        List<Node> tempCopiedChilds = new ArrayList<>();
        Map<String, Integer> tempChildWithSameName = new HashMap<>();
        for (int i = 0; i < tempChilds.getLength(); i++) {
            Node tempItem = tempChilds.item(i);
            if (tempItem.getNodeType() != Node.TEXT_NODE && tempItem.getNodeType() != Node.COMMENT_NODE) {
                // Take real nodes to find them back by number
                String tempChildNodeName = tempItem.getNodeName();
                Integer tempChildWithSameNameCount = tempChildWithSameName.get(tempChildNodeName);
                if (tempChildWithSameNameCount == null) {
                    tempChildWithSameNameCount = 1;
                } else {
                    tempChildWithSameNameCount += 1;
                }
                tempChildWithSameName.put(tempChildNodeName, tempChildWithSameNameCount);
                tempCopiedChilds.add(tempItem);
            }
        }
        Map<String, Integer> tempChildWithSameNameCounters = new HashMap<>();
        for (Node tempCopiedChild : tempCopiedChilds) {
            String tempChildNodeName = tempCopiedChild.getNodeName();
            if (tempChildWithSameName.get(tempChildNodeName) > 1) {
                Integer tempChildWithSameNameCounter = tempChildWithSameNameCounters.get(tempChildNodeName);
                if (tempChildWithSameNameCounter == null) {
                    tempChildWithSameNameCounter = 1;
                } else {
                    tempChildWithSameNameCounter += 1;
                }
                tempChildWithSameNameCounters.put(tempChildNodeName, tempChildWithSameNameCounter);
                // add a counter to find back the correct node.
                walkOverNodes(tempCopiedChild, tempNodePath + "\t" + tempChildWithSameNameCounter, aConsumer);
            } else {
                // unique child names
                walkOverNodes(tempCopiedChild, tempNodePath, aConsumer);
            }
        }
    }

    /**
     * Restores comments from the original POM into XML written by Maven.
     *
     * @param anXml          the XML written by {@link org.apache.maven.model.io.xpp3.MavenXpp3Writer}.
     * @param aModelEncoding the model encoding used to decode XML bytes.
     * @return the XML with restored comments.
     * @throws MojoExecutionException when comments cannot be restored.
     */
    public String restoreOriginalComments(String anXml, String aModelEncoding) throws MojoExecutionException {
        DocumentBuilderFactory tempDBF = DocumentBuilderFactory.newInstance();
        DocumentBuilder tempDB;
        try {
            tempDB = tempDBF.newDocumentBuilder();
            String tempEncoding = aModelEncoding == null ? "UTF-8" : aModelEncoding; // default encoding UTF-8 when
            // nothing in pom model.
            Document tempPom = tempDB.parse(new ByteArrayInputStream(anXml.getBytes(tempEncoding)));
            Node tempNode = tempPom.getDocumentElement();
            walkOverNodes(tempNode, ".", (newNode, nodePath) -> {
                Node tempOriginalNode = commentsPaths.get(nodePath);
                if (tempOriginalNode != null) {
                    String tempOriginalNodeName = tempOriginalNode.getNodeName();
                    if (tempOriginalNodeName.equals(newNode.getNodeName())) {
                        // found matching node
                        Node tempRefChild = newNode;
                        Node tempPotentialCommentOrText = tempOriginalNode.getPreviousSibling();
                        while (tempPotentialCommentOrText != null
                                && tempPotentialCommentOrText.getNodeType() == Node.TEXT_NODE) {
                            // skip text in the original xml node
                            tempPotentialCommentOrText = tempPotentialCommentOrText.getPreviousSibling();
                        }
                        while (tempPotentialCommentOrText != null
                                && tempPotentialCommentOrText.getNodeType() == Node.COMMENT_NODE) {
                            // copy the node to be able to call previoussibling for next element
                            Node tempRefPrevious = tempRefChild.getPreviousSibling();
                            String tempWhitespaceTextBeforeRefNode = null;
                            if (tempRefPrevious != null && tempRefPrevious.getNodeType() == Node.TEXT_NODE) {
                                tempWhitespaceTextBeforeRefNode = tempRefPrevious.getNodeValue();
                            }
                            Node tempNewComment;
                            tempNewComment = tempPom.createComment(tempPotentialCommentOrText.getNodeValue());
                            tempRefChild.getParentNode().insertBefore(tempNewComment, tempRefChild);
                            // copy the whitespaces between comment and refNode
                            if (tempWhitespaceTextBeforeRefNode != null) {
                                tempRefChild
                                        .getParentNode()
                                        .insertBefore(
                                                tempPom.createTextNode(tempWhitespaceTextBeforeRefNode), tempRefChild);
                            }

                            tempRefChild = tempNewComment;

                            tempPotentialCommentOrText = tempPotentialCommentOrText.getPreviousSibling();
                            while (tempPotentialCommentOrText != null
                                    && tempPotentialCommentOrText.getNodeType() == Node.TEXT_NODE) {
                                // skip text in the original xml node
                                tempPotentialCommentOrText = tempPotentialCommentOrText.getPreviousSibling();
                            }
                        }
                    }
                }
            });
            return writeDocumentToString(tempPom);
        } catch (ParserConfigurationException
                 | SAXException
                 | IOException
                 | ClassNotFoundException
                 | InstantiationException
                 | IllegalAccessException
                 | ClassCastException e) {
            throw new MojoExecutionException("Cannot add  comments", e);
        }
    }

    /**
     * Use an LSSerializer to keep whitespaces added by MavenXpp3Writer
     *
     * @param aPom the POM document to write to a string.
     * @return the serialized POM document.
     * @throws ClassNotFoundException if the DOM implementation class cannot be loaded.
     * @throws InstantiationException if the DOM implementation cannot be instantiated.
     * @throws IllegalAccessException if the DOM implementation cannot be accessed.
     */
    private String writeDocumentToString(Document aPom)
            throws ClassNotFoundException, InstantiationException, IllegalAccessException {
        DOMImplementationRegistry registry = DOMImplementationRegistry.newInstance();
        DOMImplementationLS impl = (DOMImplementationLS) registry.getDOMImplementation("LS");
        LSOutput output = impl.createLSOutput();
        output.setEncoding("UTF-8");
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        output.setByteStream(outStream);
        LSSerializer writer = impl.createLSSerializer();
        writer.write(aPom, output);
        return outStream.toString();
    }

    /**
     * Returns the Maven logger used by this comment keeper.
     *
     * @return the Maven logger.
     */
    public Log getLog() {
        return log;
    }

    /**
     * Sets the Maven logger used by this comment keeper.
     *
     * @param aLog the Maven logger.
     */
    public void setLog(Log aLog) {
        log = aLog;
    }

}
