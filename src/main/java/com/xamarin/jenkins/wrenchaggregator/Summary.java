/*
The MIT License

Copyright (c) 2013, Red Hat, Inc.
Copyright (c) 2015, Xamarin, Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
*/
package com.xamarin.jenkins.wrenchaggregator;

import hudson.Extension;
import hudson.matrix.Combination;
import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixConfiguration;
import hudson.matrix.MatrixProject;
import hudson.matrix.MatrixRun;
import hudson.plugins.git.*;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.InvisibleAction;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TransientProjectActionFactory;
import hudson.util.RunList;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.jvnet.hudson.plugins.groovypostbuild.GroovyPostbuildSummaryAction;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

public class Summary extends InvisibleAction {

    final private AbstractProject<?, ?> project;
    final private Combination Axis;
    private int lastColspan = 0;
    private HashMap statusCache = new HashMap();
    private Run lastKnownBuild;
    private ArrayList<String> cachedStepHeaders;

    public Summary(@SuppressWarnings("rawtypes") AbstractProject project, Combination Axis) {
        this.project = project;
        this.Axis = Axis;
    }

    public RunList<?> getBuilds() {
        return project.getBuilds().limit(30);
    }

    public String getSha1(AbstractBuild<?, ?> target) {
        try {
            if (((GitSCM) (project.getScm())).getBuildData(target) != null) {
                if (((GitSCM) (project.getScm())).getBuildData(target).getLastBuiltRevision() != null) {
                    return ((GitSCM) (project.getScm())).getBuildData(target).getLastBuiltRevision().getSha1String();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public String getRepoUrl() {
        try {
            if (((GitSCM) (project.getScm())).getBrowser() != null) {
                return ((GitSCM) (project.getScm())).getBrowser().getRepoUrl();
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    public Boolean getIsMatrix() {
        return (project instanceof MatrixProject && Axis == null);
    }

    public Boolean getIsAxis() {
        return (Axis != null);
    }

    public String getAxisLabel() {
        return (Axis == null) ? "" : Axis.toString();
    }

    private void MatrixVacuum() {
        RunList<?> runsRightNow = getBuilds();
        HashMap newStatusCache = new HashMap(statusCache);
        for (Object currentJob : statusCache.keySet()) {
            if (!runsRightNow.contains(((MatrixRun) currentJob).getParentBuild())) {
                newStatusCache.remove(currentJob);
            }
        }
        if (newStatusCache != statusCache) {
            statusCache = newStatusCache;
        }
    }

    private void Vacuum() {
        if (getIsMatrix()) {
            MatrixVacuum();
            return;
        }
        RunList<?> runsRightNow = getBuilds();
        HashMap newStatusCache = new HashMap(statusCache);
        for (Object currentJob : statusCache.keySet()) {
            if (!runsRightNow.contains(currentJob)) {
                newStatusCache.remove(currentJob);
            }
        }
        if (newStatusCache != statusCache) {
            statusCache = newStatusCache;
        }
    }

    public ArrayList<String> getMatrixStepHeaders() {
        if (lastKnownBuild != null && cachedStepHeaders.size() > 0 && getBuilds().getLastBuild().equals(lastKnownBuild)) {
            return cachedStepHeaders;
        }
        lastKnownBuild = getBuilds().getLastBuild();
        cachedStepHeaders = new ArrayList<String>();
        Vacuum();
        for (Run outertarget : getBuilds()) {
            for (MatrixRun target : ((MatrixBuild) (outertarget)).getExactRuns()) {
                if (target.getActions(GroovyPostbuildSummaryAction.class).toArray().length > 0) {
                    String rawStatus = ((GroovyPostbuildSummaryAction) (target.getActions(GroovyPostbuildSummaryAction.class).toArray()[0])).getText();
                    rawStatus = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" + rawStatus.substring(rawStatus.indexOf("</h1>") + 5);
                    try {
                        XPath xpath = XPathFactory.newInstance().newXPath();
                        InputSource inputSource = new InputSource(new StringReader(rawStatus));
                        NodeList nodes = (NodeList) xpath.evaluate("/table/tr/td[position()=1]", inputSource, XPathConstants.NODESET);
                        String lastSeen = null;
                        for (int i = 0; i < nodes.getLength(); i++) {
                            if (!cachedStepHeaders.contains(nodes.item(i).getTextContent())) {
                                int index = (lastSeen == null) ? 0 : cachedStepHeaders.indexOf(lastSeen) + 1;
                                cachedStepHeaders.add(index, nodes.item(i).getTextContent());
                            }
                            lastSeen = nodes.item(i).getTextContent();
                        }
                    } catch (Exception e) {
                        cachedStepHeaders = new ArrayList<String>();
                        return cachedStepHeaders;
                    }
                }
            }
        }
        lastColspan = cachedStepHeaders.size();
        return cachedStepHeaders;
    }

    public ArrayList<String> getStepHeaders() {
        if (getIsMatrix()) {
            return getMatrixStepHeaders();
        }
        if (lastKnownBuild != null && cachedStepHeaders.size() > 0 && getBuilds().getLastBuild().equals(lastKnownBuild)) {
            return cachedStepHeaders;
        }
        lastKnownBuild = getBuilds().getLastBuild();
        cachedStepHeaders = new ArrayList<String>();
        Vacuum();
        for (Run target : getBuilds()) {
            if (target.getActions(GroovyPostbuildSummaryAction.class).toArray().length > 0) {
                String rawStatus = ((GroovyPostbuildSummaryAction) (target.getActions(GroovyPostbuildSummaryAction.class).toArray()[0])).getText();
                rawStatus = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" + rawStatus.substring(rawStatus.indexOf("</h1>") + 5);
                try {
                    XPath xpath = XPathFactory.newInstance().newXPath();
                    InputSource inputSource = new InputSource(new StringReader(rawStatus));
                    NodeList nodes = (NodeList) xpath.evaluate("/table/tr/td[position()=1]", inputSource, XPathConstants.NODESET);
                    String lastSeen = null;
                    for (int i = 0; i < nodes.getLength(); i++) {
                        if (!cachedStepHeaders.contains(nodes.item(i).getTextContent())) {
                            int index = (lastSeen == null) ? 0 : cachedStepHeaders.indexOf(lastSeen) + 1;
                            cachedStepHeaders.add(index, nodes.item(i).getTextContent());
                        }
                        lastSeen = nodes.item(i).getTextContent();
                    }
                } catch (Exception e) {
                    cachedStepHeaders = new ArrayList<String>();
                    return cachedStepHeaders;
                }
            }
        }
        lastColspan = cachedStepHeaders.size();
        return cachedStepHeaders;
    }

    public ArrayList<String> getMatrixVariables(MatrixBuild target) {
        ArrayList<String> results = new ArrayList<String>();
        List<MatrixRun> rawResults = target.getExactRuns();
        for (MatrixRun myRun : rawResults) {
            results.add(myRun.getBuildVariables().values().iterator().next());
        }
        return results;
    }

    public String getMatrixSummary(MatrixBuild target) {
        StringBuilder result = new StringBuilder();
        MatrixRun current;
        for (int i = 0; i < target.getExactRuns().size(); i++) {
            current = target.getExactRuns().get(i);
            result.append("<td class=\"wrench\" style=\"background-color: #");
            result.append(getColor(current));
            result.append("\"><a href=\"");
            result.append(current.getAbsoluteUrl());
            result.append("\">");
            result.append(current.getBuildVariables().values().iterator().next());
            result.append("</a></td>");
            result.append(getSummary(current));
            if (i != target.getExactRuns().size() - 1) {
                result.append("</tr><tr>");
            }
        }
        return result.toString();
    }

    public String getSummary(AbstractBuild<?, ?> target) {
        if (statusCache.containsKey(target)) {
            return (String) (statusCache.get(target));
        }
        StringBuilder result = new StringBuilder();
        result.append("<td class=\"wrench\">");
        if (target.getBuiltOn() != null) {
            result.append(target.getBuiltOn().getNodeName());
        } else {
            result.append("¯\\_(ツ)_/¯");
        }
        result.append("</td>");
        try {
            String rawStatus = ((GroovyPostbuildSummaryAction) (target.getActions(GroovyPostbuildSummaryAction.class).toArray()[0])).getText();
            rawStatus = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" + rawStatus.substring(rawStatus.indexOf("</h1>") + 5);
            XPath xpath = XPathFactory.newInstance().newXPath();
            InputSource inputSource = new InputSource(new StringReader(rawStatus));
            NodeList nodes = (NodeList) xpath.evaluate("/table/tr", inputSource, XPathConstants.NODESET);
            HashMap nodesStatus = new HashMap();
            String foundItem;
            for (int i = 0; i < nodes.getLength(); i++) {
                foundItem = nodes.item(i).getChildNodes().item(3).getTextContent();
                if (foundItem.equals("Unstable")) {
                    foundItem = "Unstable;" + target.getAbsoluteUrl() + nodes.item(i).getChildNodes().item(3).getChildNodes().item(0).getAttributes().getNamedItem("href").getNodeValue();
                } else if (foundItem.equals("Failed")) {
                    foundItem = "Failed;" + target.getAbsoluteUrl() + nodes.item(i).getChildNodes().item(3).getChildNodes().item(0).getAttributes().getNamedItem("href").getNodeValue();
                }
                nodesStatus.put(nodes.item(i).getChildNodes().item(0).getTextContent(), foundItem);
            }
            for (String column : getStepHeaders()) {
                if (nodesStatus.containsKey(column)) {
                    if (((String) (nodesStatus.get(column))).startsWith("Failed")) {
                        result.append("<td class=\"wrench\" style=\"background-color: #ff0000;\"><a title=\"");
                        result.append(column);
                        result.append("\" href=\"");
                        result.append(((String) (nodesStatus.get(column))).substring(7));
                        result.append("\">✗</a></td>");
                    } else if (nodesStatus.get(column).equals("Passed")) {
                        result.append("<td class=\"wrench\" style=\"background-color: #00ff7f;\">✓</td>");
                    } else if (((String) (nodesStatus.get(column))).startsWith("Unstable")) {
                        result.append("<td class=\"wrench\" style=\"background-color: #ffa500;\"><a title=\"");
                        result.append(column);
                        result.append("\" href=\"");
                        result.append(((String) (nodesStatus.get(column))).substring(9));
                        result.append("\">☹</a></td>");
                    } else if (nodesStatus.get(column).equals("Skipped")) {
                        result.append("<td class=\"wrench\" style=\"background-color: #000000; color: #ffffff;\">⌛</td>");
                    } else {
                        result.append("<td class=\"wrench\" style=\"background-color: #d3d3d3;\">?</td>");
                    }
                } else {
                    result.append("<td class=\"wrench\"></td>");
                }
            }
            statusCache.put(target, result.toString());
            return result.toString();
        } catch (Exception e) {
            if (target.getResult() != null) {
                if (target.getResult().isCompleteBuild()) {
                    result.append("<td class=\"wrench\" colspan=\"" + ((this.lastColspan > 21) ? this.lastColspan : 21) + "\" style=\"background-color: #ff0000;\">NO TEST RESULTS FOUND</td>");
                    return result.toString();
                } else if (target.getResult().equals(Result.ABORTED)) {
                    result.append("<td class=\"wrench\" colspan=\"" + ((this.lastColspan > 7) ? this.lastColspan : 7) + "\" style=\"background-color: #92675c;\">ABORTED</td>" );
                    statusCache.put(target, result.toString());
                    return result.toString();
                } else {
                    result.append("<td class=\"wrench\" colspan=\"" + ((this.lastColspan > 6) ? this.lastColspan : 6) + "\" style=\"background-color: #000000; color: #ffffff;\">ERROR</td>");
                    return result.toString();
                }
            } else {
                result.append("<td class=\"wrench\" colspan=\"" + ((this.lastColspan > 7) ? this.lastColspan : 7) + "\" style=\"background-color: #ffff00;\"><a href=\"" + target.getAbsoluteUrl() + "/console\">RUNNING</a></td>");
                return result.toString();
            }
        }
    }

    public String getColor(AbstractBuild<?, ?> target) {
        if (target.getResult() != null) {
            if (target.getResult().isCompleteBuild()) {
                if (target.getResult().equals(Result.SUCCESS)) {
                    return "00ff7f";
                } else if (target.getResult().equals(Result.UNSTABLE)) {
                    return "ffa500";
                } else if (target.getResult().equals(Result.FAILURE)) {
                    return "ff0000";
                } else {
                    return "d3d3d3";
                }
            } else if (target.getResult().equals(Result.ABORTED)) {
                return "92675c";
            }
        }
        return "ffff00";
    }

    @Override
    public String toString() {
        return "Wrench aggregation for " + project.toString();
    }

    @Extension
    public static class SummaryFactory extends TransientProjectActionFactory {

        /**
         * For matrix projects parameter actions are attached to the
         * MatrixProject
         */
        @Override
        public Collection<? extends Action> createFor(
                @SuppressWarnings("rawtypes") AbstractProject target
        ) {
            Combination Axis = (target instanceof MatrixConfiguration) ? ((MatrixConfiguration) target).getCombination() : null;

            return Arrays.asList(new Summary(target, Axis));
        }
    }
}
