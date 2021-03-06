package networkservlet;

import com.atlassian.soy.renderer.SoyException;
import com.atlassian.soy.renderer.SoyTemplateRenderer;

import com.atlassian.stash.content.Changeset;
import com.atlassian.stash.repository.*;
import com.atlassian.stash.commit.CommitService;
import com.atlassian.stash.user.SecurityService;
import com.atlassian.plugin.webresource.WebResourceManager;
import com.atlassian.stash.exception.NoSuchEntityException;

import com.atlassian.stash.commit.graph.*;
import com.atlassian.stash.user.StashAuthenticationContext;
import com.atlassian.stash.user.StashUser;
import com.atlassian.stash.util.*;
import com.atlassian.stash.scm.ScmService;
import com.google.common.collect.ImmutableMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NetworkServlet extends HttpServlet {
    private static final Logger log = LoggerFactory.getLogger(NetworkServlet.class);

    static final String NETWORK_PAGE = "stash.plugin.network";
    static final String NETWORK_PAGE_FRAGMENT = "stash.plugin.network_fragment";

    private final RepositoryService repositoryService;
    private final RepositoryMetadataService repositoryMetadataService;
    private final SoyTemplateRenderer soyTemplateRenderer;
    private final CommitService commitService;
    private final WebResourceManager webResourceManager;
    private final SecurityService securityService;
    private final StashAuthenticationContext stashAuthenticationContext;
    private final ScmService scmService;

    public NetworkServlet(SoyTemplateRenderer soyTemplateRenderer,
                          RepositoryService repositoryService,
                          RepositoryMetadataService repositoryMetadataService,
                          CommitService commitService,
                          WebResourceManager webResourceManager,
                          SecurityService securityService,
                          StashAuthenticationContext stashAuthenticationContext,
                          ScmService scmService) {
        this.soyTemplateRenderer = soyTemplateRenderer;
        this.repositoryService = repositoryService;
        this.repositoryMetadataService = repositoryMetadataService;
        this.commitService = commitService;
        this.webResourceManager = webResourceManager;
        this.securityService = securityService;
        this.stashAuthenticationContext = stashAuthenticationContext;
        this.scmService = scmService;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // Get repoSlug from path
        String pathInfo = req.getPathInfo();
        String[] components = pathInfo.split("/");
        Boolean contentsOnly = !(req.getParameter("contentsOnly") == null);
        String pageStr = req.getParameter("page");
        Integer page = Math.max(Integer.parseInt(pageStr == null ? "0" : pageStr), 1) - 1;
        final Integer limit = 50;
        final Integer offset = page * limit;

        if (components.length < 3) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        final Repository repository = repositoryService.getBySlug(components[1], components[2]);
        final StashUser user = stashAuthenticationContext.getCurrentUser();
        // Ensure we have a valid repository and user
        if (repository == null || user == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        Map<String, List<Ref>> labels = getLabels(repository);
        Page<Changeset> changesets = this.getChangesets(repository, labels, limit, offset);

        webResourceManager.requireResource("com.plugin.commitgraph.commitgraph:commitgraph-resources");
        render(resp, (contentsOnly ? NETWORK_PAGE_FRAGMENT : NETWORK_PAGE), ImmutableMap.<String, Object>of(
            "repository", repository,
            "changesetPage", changesets,
            "labels", labels,
            "limit", limit,
            "page", (page + 1)
        ));
    }

    protected Map<String, List<Ref>> getLabels(Repository repository) {
        final Map<String, List<Ref>> labels = new HashMap<String, List<Ref>>();
        scmService.getCommandFactory(repository).heads(new AbstractRefCallback() {
            @Override
            public boolean onRef(@Nonnull Ref ref) {
                List<Ref> refs = labels.get(ref.getLatestChangeset());
                if (refs == null) {
                    labels.put(ref.getLatestChangeset(), refs = new ArrayList<Ref>());
                }
                refs.add(ref);
                return true;
            }
        }).call();
        return labels;
    }

    protected Page<Changeset> getChangesets(final Repository repository,
                                            final Map<String, List<Ref>> labels,
                                            final Integer limit,
                                            final Integer offset) {
        final ArrayList<Changeset> changesets = new ArrayList<Changeset>();
        TraversalRequest request = new TraversalRequest.Builder().repository(repository).include(labels.keySet()).build();
        final Integer counter = 0;
        long startTime = System.currentTimeMillis();
        final StashUser user = stashAuthenticationContext.getCurrentUser();
        commitService.traverse(request, new TraversalCallback() {
            private Integer counter;
            @Override
            public void onStart(TraversalContext context) {
                                                        this.counter = 0;
                                                                         }
            @Override
            public TraversalStatus onNode(final CommitGraphNode node) {
                Boolean captured = false;
                if (counter >= offset && counter < (offset + limit)) {
                    securityService.impersonating(user, "Reading repository changesets")
                        .call(new UncheckedOperation<Boolean>() {
                            @Override
                            public Boolean perform() {
                                changesets.add(commitService.getChangeset(repository, node.getCommit().getId()));
                                return true;
                            }
                        });
                    captured = true;
                } else if (counter >= (offset + limit)) {
                    return TraversalStatus.FINISH;
                }
                // If there are no parents then we are at the root node.
                if (node.getParents().size() > 0) {
                    counter++;
                    return TraversalStatus.CONTINUE;
                } else {
                    return TraversalStatus.FINISH;
                }
            }
        });
        // System.out.println("Graph traversal time: " + String.valueOf(System.currentTimeMillis() - startTime) + "ms");
        // Convert the arraylist to a page
        return PageUtils.createPage(changesets, PageUtils.newRequest(0, changesets.size()));
    }

    protected void render(HttpServletResponse resp, String templateName, Map<String, Object> data) throws IOException, ServletException {
        resp.setContentType("text/html;charset=UTF-8");
        try {
            soyTemplateRenderer.render(
                resp.getWriter(),
                "com.plugin.commitgraph.commitgraph:network-soy-templates",
                templateName,
                data
            );
        } catch (SoyException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new ServletException(e);
        }
    }
}
