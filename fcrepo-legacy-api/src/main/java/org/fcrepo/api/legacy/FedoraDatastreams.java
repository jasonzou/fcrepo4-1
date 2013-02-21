
package org.fcrepo.api.legacy;

import static com.google.common.collect.ImmutableSet.builder;
import static java.util.Collections.singletonList;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.APPLICATION_OCTET_STREAM_TYPE;
import static javax.ws.rs.core.MediaType.TEXT_XML;
import static javax.ws.rs.core.Response.created;
import static javax.ws.rs.core.Response.ok;
import static org.fcrepo.api.legacy.FedoraObjects.getObjectSize;
import static org.fcrepo.jaxb.responses.DatastreamProfile.DatastreamStates.A;
import static org.fcrepo.services.DatastreamService.createDatastreamNode;
import static org.fcrepo.services.DatastreamService.getDatastreamNode;
import static org.fcrepo.services.ObjectService.getObjectNode;
import static org.modeshape.jcr.api.JcrConstants.JCR_CONTENT;
import static org.modeshape.jcr.api.JcrConstants.JCR_DATA;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.ValueFormatException;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.cxf.jaxrs.ext.multipart.Attachment;
import org.fcrepo.AbstractResource;
import org.fcrepo.Datastream;
import org.fcrepo.jaxb.responses.DatastreamHistory;
import org.fcrepo.jaxb.responses.DatastreamProfile;
import org.fcrepo.jaxb.responses.ObjectDatastreams;
import org.fcrepo.jaxb.responses.ObjectDatastreams.DatastreamElement;
import org.fcrepo.services.DatastreamService;
import org.modeshape.jcr.api.Binary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet.Builder;

@Path("/objects/{pid}/datastreams")
public class FedoraDatastreams extends AbstractResource {

    final private Logger logger = LoggerFactory
            .getLogger(FedoraDatastreams.class);

    /**
     * Returns a list of datastreams for the object
     * 
     * @param pid
     *            persistent identifier of the digital object
     * @return the list of datastreams
     * @throws RepositoryException
     * @throws IOException
     * @throws TemplateException
     */

    @GET
    @Path("/")
    @Produces({TEXT_XML, APPLICATION_JSON})
    public Response getDatastreams(@PathParam("pid")
    final String pid) throws RepositoryException, IOException {

        final ObjectDatastreams objectDatastreams = new ObjectDatastreams();
        final Builder<DatastreamElement> datastreams = builder();

        NodeIterator i = getObjectNode(pid).getNodes();
        while (i.hasNext()) {
            final Node ds = i.nextNode();
            datastreams.add(new DatastreamElement(ds.getName(), ds.getName(),
                    getDSMimeType(ds)));
        }
        objectDatastreams.datastreams = datastreams.build();
        return ok(objectDatastreams).build();

    }

    @POST
    @Path("/")
    public Response addDatastreams(@PathParam("pid")
    final String pid, final List<Attachment> attachmentList)
            throws RepositoryException, IOException {

        final Session session = repo.login();
        try {
            Long oldObjectSize =
                    getObjectSize(session.getNode("/objects/" + pid));

            for (final Attachment a : attachmentList) {
                final String dsid =
                        a.getContentDisposition().getParameter("name");
                final String dsPath = "/objects/" + pid + "/" + dsid;
                createDatastreamNode(session, dsPath, a.getDataHandler()
                        .getContentType(), a.getDataHandler().getInputStream());

            }
            session.save();

            /*
             * we save before updating the repo size because the act of
             * persisting session state creates new system-curated nodes and
             * properties which contribute to the footprint of this resource
             */
            updateRepositorySize(getObjectSize(session.getNode("/objects/" +
                    pid)) -
                    oldObjectSize, session);
            // now we save again to persist the repo size
            session.save();
        } finally {
            session.logout();
        }

        return created(uriInfo.getAbsolutePath()).build();
    }

    /**
     * Create a new datastream
     * 
     * @param pid
     *            persistent identifier of the digital object
     * @param dsid
     *            datastream identifier
     * @param contentType
     *            Content-Type header
     * @param requestBodyStream
     *            Binary blob
     * @return 201 Created
     * @throws RepositoryException
     * @throws IOException
     */
    @POST
    @Path("/{dsid}")
    public Response addDatastream(@PathParam("pid")
    final String pid, @PathParam("dsid")
    final String dsid, @HeaderParam("Content-Type")
    MediaType contentType, InputStream requestBodyStream)
            throws RepositoryException, IOException {
        final Session session = repo.login();

        contentType =
                contentType != null ? contentType
                        : APPLICATION_OCTET_STREAM_TYPE;
        String dspath = "/objects/" + pid + "/" + dsid;

        if (!session.nodeExists(dspath)) {
            return created(
                    addDatastreamNode(pid, dspath, contentType,
                            requestBodyStream, session)).build();
        } else {
            session.getNode(dspath).remove();
            session.save();
            return created(
                    addDatastreamNode(pid, dspath, contentType,
                            requestBodyStream, session)).build();
        }

    }

    /**
     * Modify an existing datastream's content
     * 
     * @param pid
     *            persistent identifier of the digital object
     * @param dsid
     *            datastream identifier
     * @param contentType
     *            Content-Type header
     * @param requestBodyStream
     *            Binary blob
     * @return 201 Created
     * @throws RepositoryException
     * @throws IOException
     */
    @PUT
    @Path("/{dsid}")
    public Response modifyDatastream(@PathParam("pid")
    final String pid, @PathParam("dsid")
    final String dsid, @HeaderParam("Content-Type")
    MediaType contentType, InputStream requestBodyStream)
            throws RepositoryException, IOException {
        final Session session = repo.login();
        contentType =
                contentType != null ? contentType
                        : MediaType.APPLICATION_OCTET_STREAM_TYPE;
        String dspath = "/objects/" + pid + "/" + dsid;

        return created(
                addDatastreamNode(pid, dspath, contentType, requestBodyStream,
                        session)).build();

    }

    private URI addDatastreamNode(final String pid, final String dsPath,
            final MediaType contentType, final InputStream requestBodyStream,
            final Session session) throws RepositoryException, IOException {

        Long oldObjectSize = getObjectSize(session.getNode("/objects/" + pid));
        logger.debug("Attempting to add datastream node at path: " + dsPath);
        try {
            boolean created = session.nodeExists(dsPath);
            createDatastreamNode(session, dsPath, contentType.toString(),
                    requestBodyStream);
            session.save();
            if (created) {
                /*
                 * we save before updating the repo size because the act of
                 * persisting session state creates new system-curated nodes and
                 * properties which contribute to the footprint of this resource
                 */
                updateRepositorySize(getObjectSize(session.getNode("/objects/" +
                        pid)) -
                        oldObjectSize, session);
                // now we save again to persist the repo size
                session.save();
            }
        } finally {
            session.logout();
        }
        logger.debug("Finished adding datastream node at path: " + dsPath);
        return uriInfo.getAbsolutePath();
    }

    /**
     * Get the datastream profile of a datastream
     * 
     * @param pid
     *            persistent identifier of the digital object
     * @param dsid
     *            datastream identifier
     * @return 200
     * @throws RepositoryException
     * @throws IOException
     * @throws TemplateException
     */
    @GET
    @Path("/{dsid}")
    @Produces({TEXT_XML, APPLICATION_JSON})
    public Response getDatastream(@PathParam("pid")
    final String pid, @PathParam("dsid")
    final String dsid) throws RepositoryException, IOException {

        return ok(getDSProfile(getDatastreamNode(pid, dsid))).build();

    }

    /**
     * Get the binary content of a datastream
     * 
     * @param pid
     *            persistent identifier of the digital object
     * @param dsid
     *            datastream identifier
     * @return Binary blob
     * @throws RepositoryException
     */
    @GET
    @Path("/{dsid}/content")
    public Response getDatastreamContent(@PathParam("pid")
    final String pid, @PathParam("dsid")
    final String dsid) throws RepositoryException {

        final Datastream ds = DatastreamService.getDatastream(pid, dsid);
        return ok(ds.getContent(), ds.getMimeType()).build();
    }

    /**
     * Get previous version information for this datastream
     * 
     * @param pid
     *            persistent identifier of the digital object
     * @param dsid
     *            datastream identifier
     * @return 200
     * @throws RepositoryException
     * @throws IOException
     * @throws TemplateException
     */
    @GET
    @Path("/{dsid}/versions")
    @Produces({TEXT_XML, APPLICATION_JSON})
    // TODO implement this after deciding on a versioning model
            public
            Response getDatastreamHistory(@PathParam("pid")
            final String pid, @PathParam("dsid")
            final String dsid) throws RepositoryException, IOException {

        final Datastream ds = DatastreamService.getDatastream(pid, dsid);
        final DatastreamHistory dsHistory =
                new DatastreamHistory(singletonList(getDSProfile(ds.getNode())));
        dsHistory.dsID = dsid;
        dsHistory.pid = pid;
        return ok(dsHistory).build();
    }

    /**
     * Get previous version information for this datastream. See
     * /{dsid}/versions. Kept for compatibility with fcrepo <3.5 API.
     * 
     * @deprecated
     * 
     * @param pid
     *            persistent identifier of the digital object
     * @param dsid
     *            datastream identifier
     * @return 200
     * @throws RepositoryException
     * @throws IOException
     * @throws TemplateException
     */
    @GET
    @Path("/{dsid}/history")
    @Produces(TEXT_XML)
    @Deprecated
    public Response getDatastreamHistoryOld(@PathParam("pid")
    final String pid, @PathParam("dsid")
    final String dsid) throws RepositoryException, IOException {
        return getDatastreamHistory(pid, dsid);
    }

    /**
     * Purge the datastream
     * 
     * @param pid
     *            persistent identifier of the digital object
     * @param dsid
     *            datastream identifier
     * @return 204
     * @throws RepositoryException
     */
    @DELETE
    @Path("/{dsid}")
    public Response deleteDatastream(@PathParam("pid")
    String pid, @PathParam("dsid")
    String dsid) throws RepositoryException {
        final String dsPath = "/objects/" + pid + "/" + dsid;
        final Session session = repo.login();
        final Node ds = session.getNode(dsPath);
        updateRepositorySize(0L - getDatastreamSize(ds), session);
        return deleteResource(ds);
    }

    private DatastreamProfile getDSProfile(Node ds) throws RepositoryException,
            IOException {
        final DatastreamProfile dsProfile = new DatastreamProfile();
        dsProfile.dsID = ds.getName();
        dsProfile.pid = ds.getParent().getName();
        dsProfile.dsLabel = ds.getName();
        dsProfile.dsState = A;
        dsProfile.dsMIME = getDSMimeType(ds);
        dsProfile.dsSize =
                getNodePropertySize(ds) +
                        ds.getNode(JCR_CONTENT).getProperty(JCR_DATA)
                                .getBinary().getSize();
        dsProfile.dsCreateDate = ds.getProperty("jcr:created").getString();
        return dsProfile;
    }

    private String getDSMimeType(Node ds) throws ValueFormatException,
            PathNotFoundException, RepositoryException, IOException {
        final Binary b =
                (Binary) ds.getNode(JCR_CONTENT).getProperty(JCR_DATA)
                        .getBinary();
        return b.getMimeType();
    }

    public static Long getDatastreamSize(Node ds) throws ValueFormatException,
            PathNotFoundException, RepositoryException {
        return getNodePropertySize(ds) + getContentSize(ds);
    }

    public static Long getContentSize(Node ds) throws ValueFormatException,
            PathNotFoundException, RepositoryException {
        return ds.getNode(JCR_CONTENT).getProperty(JCR_DATA).getBinary()
                .getSize();
    }

}
