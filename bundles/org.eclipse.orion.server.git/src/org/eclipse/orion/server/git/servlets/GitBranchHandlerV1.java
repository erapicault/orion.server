/*******************************************************************************
 * Copyright (c) 2011 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.orion.server.git.servlets;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.core.runtime.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepository;
import org.eclipse.orion.internal.server.servlets.ProtocolConstants;
import org.eclipse.orion.internal.server.servlets.ServletResourceHandler;
import org.eclipse.orion.server.core.ServerStatus;
import org.eclipse.orion.server.git.GitConstants;
import org.eclipse.orion.server.servlets.OrionServlet;
import org.eclipse.osgi.util.NLS;
import org.json.*;

public class GitBranchHandlerV1 extends ServletResourceHandler<String> {
	private ServletResourceHandler<IStatus> statusHandler;

	GitBranchHandlerV1(ServletResourceHandler<IStatus> statusHandler) {
		this.statusHandler = statusHandler;
	}

	@Override
	public boolean handleRequest(HttpServletRequest request, HttpServletResponse response, String path) throws ServletException {
		try {
			switch (getMethod(request)) {
				case GET :
					return handleGet(request, response, path);
				case POST :
					return handlePost(request, response, path);
				case DELETE :
					return handleDelete(request, response, path);
			}
		} catch (Exception e) {
			String msg = NLS.bind("Failed to handle /git/branch request for {0}", path); //$NON-NLS-1$
			return statusHandler.handleRequest(request, response, new ServerStatus(IStatus.ERROR, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, msg, e));
		}
		return false;
	}

	private boolean handleGet(HttpServletRequest request, HttpServletResponse response, String path) throws IOException, JSONException, ServletException, URISyntaxException, CoreException {
		// FIXME: what if there is a branch named "file"?
		Path p = new Path(path);
		if (p.segment(0).equals("file")) { //$NON-NLS-1$
			// branch list: expected path /git/branch/file/{path}
			File gitDir = GitUtils.getGitDir(p);
			Repository db = new FileRepository(gitDir);
			Git git = new Git(db);
			List<Ref> branches = git.branchList().call();
			JSONObject result = new JSONObject();
			JSONArray children = new JSONArray();
			for (Ref ref : branches) {
				JSONObject child = toJSON(ref, getURI(request), 2);
				children.put(child);
			}
			result.put(ProtocolConstants.KEY_CHILDREN, children);
			OrionServlet.writeJSONResponse(request, response, result);
			return true;
		} else if (p.segment(1).equals("file")) { //$NON-NLS-1$
			// branch details: expected path /git/branch/{name}/file/{path}
			File gitDir = GitUtils.getGitDir(p.removeFirstSegments(1));
			Repository db = new FileRepository(gitDir);
			Git git = new Git(db);
			List<Ref> branches = git.branchList().call();
			JSONObject result = null;
			for (Ref ref : branches) {
				if (Repository.shortenRefName(ref.getName()).equals(p.segment(0))) {
					result = toJSON(ref, getURI(request), 3);
					break;
				}
			}
			if (result == null) {
				String msg = NLS.bind("Branch '{0}' not found", p.segment(0)); //$NON-NLS-1$
				return statusHandler.handleRequest(request, response, new ServerStatus(IStatus.ERROR, HttpServletResponse.SC_NOT_FOUND, msg, null));
			}
			OrionServlet.writeJSONResponse(request, response, result);
			return true;
		}

		return false;
	}

	private boolean handlePost(HttpServletRequest request, HttpServletResponse response, String path) throws IOException, JSONException, ServletException, URISyntaxException, CoreException, JGitInternalException, GitAPIException {
		Path p = new Path(path);
		// expected path /git/branch/file/{path}
		if (p.segment(0).equals("file")) { //$NON-NLS-1$

			JSONObject toCreate = OrionServlet.readJSONRequest(request);
			String branchName = toCreate.optString(GitConstants.KEY_BRANCH_NAME, null);
			if (branchName == null || branchName.isEmpty()) {
				return statusHandler.handleRequest(request, response, new ServerStatus(IStatus.ERROR, HttpServletResponse.SC_BAD_REQUEST, "Branch name must be provided", null));
			}

			File gitDir = GitUtils.getGitDir(p);
			Repository db = new FileRepository(gitDir);
			Git git = new Git(db);
			Ref ref = git.branchCreate().setName(branchName).call();
			// TODO: what if something went wrong, handle exception
			JSONObject result = toJSON(ref, getURI(request), 2);
			response.setHeader(ProtocolConstants.HEADER_LOCATION, result.getString(ProtocolConstants.KEY_LOCATION));
			response.setStatus(HttpServletResponse.SC_CREATED);
			return true;
		}
		String msg = NLS.bind("Failed to create a branch for {0}", path); //$NON-NLS-1$
		return statusHandler.handleRequest(request, response, new ServerStatus(IStatus.ERROR, HttpServletResponse.SC_BAD_REQUEST, msg, null));
	}

	private boolean handleDelete(HttpServletRequest request, HttpServletResponse response, String path) throws IOException, JSONException, ServletException, URISyntaxException, CoreException, JGitInternalException, GitAPIException {
		// FIXME: what if there is a branch named "file"?
		Path p = new Path(path);
		if (p.segment(1).equals("file")) { //$NON-NLS-1$
			// branch details: expected path /git/branch/{name}/file/{path}
			File gitDir = GitUtils.getGitDir(p.removeFirstSegments(1));
			Repository db = new FileRepository(gitDir);
			Git git = new Git(db);
			List<String> branches = git.branchDelete().setBranchNames(p.segment(0)).call();
			// TODO: do something with the result, and handle any exceptions
			return true;
		}
		return false;
	}

	private JSONObject toJSON(Ref ref, URI baseLocation, int s) throws JSONException, URISyntaxException {
		JSONObject result = new JSONObject();
		String shortName = Repository.shortenRefName(ref.getName());
		result.put(GitConstants.KEY_BRANCH_NAME, shortName);

		IPath basePath = new Path(baseLocation.getPath());
		IPath newPath = new Path(GitServlet.GIT_URI).append(GitConstants.BRANCH_RESOURCE).append(shortName).append(basePath.removeFirstSegments(s));
		URI location = new URI(baseLocation.getScheme(), baseLocation.getUserInfo(), baseLocation.getHost(), baseLocation.getPort(), newPath.toString(), baseLocation.getQuery(), baseLocation.getFragment());
		result.put(ProtocolConstants.KEY_LOCATION, location);
		return result;
	}

}