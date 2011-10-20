/*
 * Copyright (c) 2002-2011, Mairie de Paris
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 *  1. Redistributions of source code must retain the above copyright notice
 *     and the following disclaimer.
 *
 *  2. Redistributions in binary form must reproduce the above copyright notice
 *     and the following disclaimer in the documentation and/or other materials
 *     provided with the distribution.
 *
 *  3. Neither the name of 'Mairie de Paris' nor 'Lutece' nor the names of its
 *     contributors may be used to endorse or promote products derived from
 *     this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 * License 1.0
 */
package fr.paris.lutece.plugins.crm.modules.form.service.draft;

import fr.paris.lutece.plugins.crm.modules.form.service.CRMWebServices;
import fr.paris.lutece.plugins.crm.modules.form.service.signrequest.CRMRequestAuthenticatorService;
import fr.paris.lutece.plugins.crm.modules.form.util.Constants;
import fr.paris.lutece.plugins.form.business.Form;
import fr.paris.lutece.plugins.form.business.FormSubmit;
import fr.paris.lutece.plugins.form.business.Response;
import fr.paris.lutece.plugins.form.service.draft.DraftBackupService;
import fr.paris.lutece.plugins.form.utils.FormUtils;
import fr.paris.lutece.plugins.form.utils.JSONUtils;
import fr.paris.lutece.portal.service.blobstore.BlobStoreService;
import fr.paris.lutece.portal.service.i18n.I18nService;
import fr.paris.lutece.portal.service.message.SiteMessage;
import fr.paris.lutece.portal.service.message.SiteMessageException;
import fr.paris.lutece.portal.service.message.SiteMessageService;
import fr.paris.lutece.portal.service.security.LuteceUser;
import fr.paris.lutece.portal.service.security.SecurityService;
import fr.paris.lutece.portal.service.util.AppLogService;
import fr.paris.lutece.util.httpaccess.HttpAccessException;

import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;

import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;


/**
 * CRM Draft Backup Service
 */
public class CRMDraftBackupService implements DraftBackupService
{
    private static Logger _logger = Logger.getLogger( "lutece.crm" );
    private BlobStoreService _blobStoreService;

    /**
     * {@inheritDoc }
     */
    public void setBlobStoreService( BlobStoreService blobStoreService )
    {
        _blobStoreService = blobStoreService;
    }

    /**
     * {@inheritDoc}
     */
    public boolean preProcessRequest( HttpServletRequest request, Form form )
        throws SiteMessageException
    {
        if ( !isRequestAuthenticated( request ) )
        {
            SiteMessageService.setMessage( request, Constants.PROPERTY_MESSAGE_STOP_ACCESS_DENIED, SiteMessage.TYPE_STOP );
        }

        // handle delete actions
        if ( draftAction( request ) )
        {
            return true;
        }

        // create if the draft does not exist
        if ( !existsDraft( request ) )
        {
            create( request, form );
        }

        restore( request );

        return false;
    }

    /**
     * {@inheritDoc }
     */
    public void saveDraft( HttpServletRequest request, Form form )
        throws SiteMessageException
    {
        if ( _logger.isDebugEnabled(  ) )
        {
            _logger.debug( "Saving Draft ..." );
        }

        HttpSession session = request.getSession( true );

        saveResponses( FormUtils.getResponses( session ), form.getIdForm(  ), session );

        updateCRMStatus( request );
    }

    /**
     * Updates the CRM status to "in progress".
     * @param request the request
     */
    private void updateCRMStatus( HttpServletRequest request )
    {
        HttpSession session = request.getSession(  );

        // get draft blob id
        String strKey = (String) session.getAttribute( Constants.SESSION_ATTRIBUTE_DEMAND_DATA_PARAMS );
        String strIdDemand = (String) session.getAttribute( Constants.SESSION_ATTRIBUTE_ID_DEMAND_PARAMS );

        String strStatusText = I18nService.getLocalizedString( Constants.PROPERTY_CRM_STATUS_TEXT_MODIF,
                request.getLocale(  ) );

        if ( StringUtils.isNotBlank( strKey ) )
        {
            try
            {
                CRMWebServices.sendDemandUpdate( strIdDemand, Constants.CRM_STATUS_DRAFT, strStatusText, strKey );
            }
            catch ( HttpAccessException e )
            {
                _logger.error( e.getMessage(  ), e );
            }
        }
        else
        {
            _logger.error( "No draft found" );
        }
    }

    /**
     * Saves the responses
     * @param mapResponses map response
     * @param nIdForm the id form
     * @param session the session
     */
    void saveResponses( Map<Integer, List<Response>> mapResponses, int nIdForm, HttpSession session )
    {
        // get draft blob id
        String strKey = (String) session.getAttribute( Constants.SESSION_ATTRIBUTE_DEMAND_DATA_PARAMS );

        if ( StringUtils.isNotBlank( strKey ) )
        {
            String strJsonResponse = JSONUtils.buildJson( mapResponses, nIdForm );
            _blobStoreService.update( strKey, strJsonResponse.getBytes(  ) );
        }
    }

    /**
     * Saves the draft for th formSubmit
     * @param request the request
     * @param formSubmit the formsubmit
     */
    public void saveDraft( HttpServletRequest request, FormSubmit formSubmit )
    {
        if ( _logger.isDebugEnabled(  ) )
        {
            _logger.debug( "Saving formsubmit ..." );
        }

        HttpSession session = request.getSession( true );

        // build the map response
        Map<Integer, List<Response>> mapResponses = new HashMap<Integer, List<Response>>(  );

        for ( Response response : formSubmit.getListResponse(  ) )
        {
            int nIdEntry = response.getEntry(  ).getIdEntry(  );
            List<Response> listResponseEntry = mapResponses.get( nIdEntry );

            if ( listResponseEntry == null )
            {
                listResponseEntry = new ArrayList<Response>(  );
                mapResponses.put( nIdEntry, listResponseEntry );
            }

            listResponseEntry.add( response );
        }

        saveResponses( mapResponses, formSubmit.getForm(  ).getIdForm(  ), session );

        updateCRMStatus( request );
    }

    /**
     * {@inheritDoc}
     */
    public void validateDraft( HttpServletRequest request, Form form )
    {
        if ( _logger.isDebugEnabled(  ) )
        {
            _logger.debug( "Validating Draft ..." );
        }

        HttpSession session = request.getSession( true );

        String strKey = (String) session.getAttribute( Constants.SESSION_ATTRIBUTE_DEMAND_DATA_PARAMS );
        String strDemandId = (String) session.getAttribute( Constants.SESSION_ATTRIBUTE_ID_DEMAND_PARAMS );

        if ( StringUtils.isNotBlank( strKey ) )
        {
            try
            {
                String strStatusText = I18nService.getLocalizedString( Constants.PROPERTY_CRM_STATUS_TEXT_VALIDATE,
                        request.getLocale(  ) );
                CRMWebServices.sendDemandUpdate( strDemandId, Constants.CRM_STATUS_VALIDATED, strStatusText, strKey );
                _blobStoreService.delete( strKey );

                // Remove session attributes
                removeSessionAttributes( session );
            }
            catch ( HttpAccessException hae )
            {
                _logger.error( hae.getMessage(  ), hae );
            }
        }
    }

    /**
     * Check if the draft exists
     * @param request the HTTP request
     * @return true if the draft already exists, false otherwise
     */
    private boolean existsDraft( HttpServletRequest request )
    {
        HttpSession session = request.getSession( true );

        String strIdDemandType = request.getParameter( Constants.PARAM_ID_DEMAND_TYPE );
        String strIdDemand = request.getParameter( Constants.PARAM_ID_DEMAND );

        if ( StringUtils.isNotBlank( strIdDemandType ) )
        {
            return false;
        }
        else if ( StringUtils.isNotBlank( strIdDemand ) )
        {
            session.setAttribute( Constants.SESSION_ATTRIBUTE_ID_DEMAND_PARAMS, strIdDemand );

            try
            {
                String strUserGuid = CRMWebServices.getUserGuidFromIdDemand( strIdDemand );
                session.setAttribute( Constants.SESSION_ATTRIBUTE_USER_GUID_PARAMS, strUserGuid );
            }
            catch ( HttpAccessException ex )
            {
                _logger.error( "Error calling WebService : " + ex.getMessage(  ), ex );
            }

            String strDemandData = request.getParameter( Constants.PARAM_DEMAND_DATA );

            if ( StringUtils.isNotBlank( strDemandData ) )
            {
                session.setAttribute( Constants.SESSION_ATTRIBUTE_DEMAND_DATA_PARAMS, strDemandData );
            }
        }

        return true;
    }

    /**
     * Create a draft
     * @param request the HTTP request
     * @param form the form
     */
    private void create( HttpServletRequest request, Form form )
    {
        HttpSession session = request.getSession( true );

        String strDemandType = request.getParameter( Constants.PARAM_ID_DEMAND_TYPE );

        if ( StringUtils.isNotBlank( strDemandType ) )
        {
            JSONObject json = new JSONObject(  );
            json.element( JSONUtils.JSON_KEY_ID_FORM, form.getIdForm(  ) );

            // the data is only the key - no need to store any other data
            String strData = _blobStoreService.store( json.toString(  ).getBytes(  ) );

            try
            {
                // save user info and demand to CRM
                String strIdCRMUser = request.getParameter( Constants.PARAM_ID_CRM_USER );
                String strUserGuid = StringUtils.EMPTY;
                String strIdDemand = StringUtils.EMPTY;

                if ( StringUtils.isBlank( strIdCRMUser ) && SecurityService.isAuthenticationEnable(  ) )
                {
                    LuteceUser user = SecurityService.getInstance(  ).getRemoteUser( request );

                    if ( user != null )
                    {
                        strUserGuid = user.getName(  );
                    }
                }

                String strStatusText = I18nService.getLocalizedString( Constants.PROPERTY_CRM_STATUS_TEXT_NEW,
                        request.getLocale(  ) );

                if ( StringUtils.isNotBlank( strUserGuid ) )
                {
                    strIdDemand = CRMWebServices.sendDemandCreateByUserGuid( strDemandType, strUserGuid,
                            Constants.CRM_STATUS_DRAFT, strStatusText, strData );
                }
                else if ( StringUtils.isNotBlank( strIdCRMUser ) )
                {
                    strIdDemand = CRMWebServices.sendDemandCreateByIdCRMUser( strDemandType, strIdCRMUser,
                            Constants.CRM_STATUS_DRAFT, strStatusText, strData );
                }

                if ( StringUtils.isNotBlank( strIdDemand ) && !Constants.INVALID_ID.equals( strIdDemand ) )
                {
                    session.setAttribute( Constants.SESSION_ATTRIBUTE_ID_DEMAND_PARAMS, strIdDemand );

                    try
                    {
                        strUserGuid = CRMWebServices.getUserGuidFromIdDemand( strIdDemand );
                        session.setAttribute( Constants.SESSION_ATTRIBUTE_USER_GUID_PARAMS, strUserGuid );
                    }
                    catch ( HttpAccessException ex )
                    {
                        _logger.error( "Error calling WebService : " + ex.getMessage(  ), ex );
                    }

                    session.setAttribute( Constants.SESSION_ATTRIBUTE_DEMAND_DATA_PARAMS, strData );
                }
                else
                {
                    throw new Exception( "Invalid ID demand" );
                }
            }
            catch ( Exception e )
            {
                _logger.error( "Error calling WebService : " + e.getMessage(  ), e );

                // Remove the blob created previously
                _blobStoreService.delete( strData );
            }
        }
    }

    /**
     * Restore a draft
     * @param request the HTTP request
     */
    private void restore( HttpServletRequest request )
    {
        if ( _logger.isDebugEnabled(  ) )
        {
            _logger.debug( "Restoring Draft ..." );
        }

        HttpSession session = request.getSession( true );

        String strData = ( (String) session.getAttribute( Constants.SESSION_ATTRIBUTE_DEMAND_DATA_PARAMS ) );

        if ( StringUtils.isNotBlank( strData ) )
        {
            byte[] dataForm = _blobStoreService.getBlob( strData );

            if ( dataForm != null )
            {
                String strDataForm = new String( dataForm );

                if ( StringUtils.isNotBlank( strDataForm ) )
                {
                    // bind responses to session if jsonresponse has content - use default otherwise.
                    Map<Integer, List<Response>> mapResponses = JSONUtils.buildListResponses( strDataForm,
                            request.getLocale(  ), session );

                    if ( mapResponses != null )
                    {
                        if ( _logger.isDebugEnabled(  ) )
                        {
                            _logger.debug( "Found reponses - restoring form" );
                        }

                        FormUtils.restoreResponses( session, mapResponses );
                    }
                }
            }
        }
        else
        {
            AppLogService.error( "No blob id found for the current session" );
        }
    }

    /**
     * Delete a draft
     * @param request the HTTP request
     * @return <code>true</code> if an error occurs, <code>false</code> otherwise
     */
    private boolean delete( HttpServletRequest request )
    {
        if ( _logger.isDebugEnabled(  ) )
        {
            _logger.debug( "Deleting Draft ..." );
        }

        boolean bHasError = false;

        String strIdDemand = request.getParameter( Constants.PARAM_ID_DEMAND );
        String strData = request.getParameter( Constants.PARAM_DEMAND_DATA );

        if ( StringUtils.isNotBlank( strIdDemand ) && StringUtils.isNumeric( strIdDemand ) &&
                StringUtils.isNotBlank( strData ) )
        {
            try
            {
                // Delete the demand in CRM
                CRMWebServices.sendDemandDelete( strIdDemand );
                // Delete the demand in Blobstore
                _blobStoreService.delete( strData );
            }
            catch ( HttpAccessException ex )
            {
                _logger.error( "Error deleting draft : " + ex.getMessage(  ), ex );
                bHasError = true;
            }
        }
        else
        {
            bHasError = true;
        }

        return bHasError;
    }

    /**
     * Do draft action
     * @param request the HTTP request
     * @return true if there is an draft action, false otherwise
     * @throws SiteMessageException message exception if remove draft
     */
    private boolean draftAction( HttpServletRequest request )
        throws SiteMessageException
    {
        String strAction = request.getParameter( Constants.PARAMETER_ACTION_NAME );

        if ( StringUtils.isNotBlank( strAction ) )
        {
            if ( Constants.ACTION_DO_REMOVE_DRAFT.equals( strAction ) )
            {
                doRemoveDraft( request );
            }
            else if ( Constants.ACTION_REMOVE_DRAFT.equals( strAction ) )
            {
                removeDraft( request );
            }

            return true;
        }

        return false;
    }

    /**
     * Do remove a demand by calling the DraftBackUpService
     * @param request The HTTP request
     */
    private void doRemoveDraft( HttpServletRequest request )
    {
        delete( request );
    }

    /**
     * Remove a draft and display a message saying the draft has or not been deleted
     * @param request The HTTP request
     * @throws SiteMessageException the message exception
     */
    private void removeDraft( HttpServletRequest request )
        throws SiteMessageException
    {
        String strUrlReturn = request.getParameter( Constants.PARAMETER_URL_RETURN );

        if ( StringUtils.isNotBlank( strUrlReturn ) )
        {
            if ( delete( request ) )
            {
                SiteMessageService.setMessage( request, Constants.PROPERTY_MESSAGE_ERROR_CALLING_WS,
                    SiteMessage.TYPE_ERROR, strUrlReturn );
            }
            else
            {
                SiteMessageService.setMessage( request, Constants.PROPERTY_MESSAGE_INFO_REMOVE_DEMAND,
                    SiteMessage.TYPE_INFO, strUrlReturn );
            }
        }
    }

    /**
     * Check if the request is authenticated
     * @param request the HTTP request
     * @return true if it is authenticated, false otherwise
     */
    private boolean isRequestAuthenticated( HttpServletRequest request )
    {
        boolean bIsAuthenticated = true;
        String strDemandType = request.getParameter( Constants.PARAM_ID_DEMAND_TYPE );
        String strDemand = request.getParameter( Constants.PARAM_ID_DEMAND );
        String strAction = request.getParameter( Constants.PARAMETER_ACTION_NAME );

        if ( StringUtils.isNotBlank( strAction ) && Constants.ACTION_DO_REMOVE_DRAFT.equals( strAction ) )
        {
            bIsAuthenticated = CRMRequestAuthenticatorService.getRequestAuthenticatorForWS(  )
                                                             .isRequestAuthenticated( request );
        }
        else if ( StringUtils.isNotBlank( strDemandType ) || StringUtils.isNotBlank( strDemand ) ||
                ( StringUtils.isNotBlank( strAction ) && Constants.ACTION_REMOVE_DRAFT.equals( strAction ) ) )
        {
            bIsAuthenticated = CRMRequestAuthenticatorService.getRequestAuthenticatorForUrl(  )
                                                             .isRequestAuthenticated( request );
        }

        return bIsAuthenticated;
    }

    /**
     * Remove the session attributes
     * @param session the session
     */
    private void removeSessionAttributes( HttpSession session )
    {
        session.removeAttribute( Constants.SESSION_ATTRIBUTE_DEMAND_DATA_PARAMS );
        session.removeAttribute( Constants.SESSION_ATTRIBUTE_ID_DEMAND_PARAMS );
        session.removeAttribute( Constants.SESSION_ATTRIBUTE_DEMAND_DATA_PARAMS );
    }
}
