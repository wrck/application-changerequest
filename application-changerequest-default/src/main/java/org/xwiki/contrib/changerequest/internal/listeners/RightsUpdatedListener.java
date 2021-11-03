/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.changerequest.internal.listeners;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.changerequest.ChangeRequest;
import org.xwiki.contrib.changerequest.ChangeRequestConfiguration;
import org.xwiki.contrib.changerequest.ChangeRequestException;
import org.xwiki.contrib.changerequest.ChangeRequestRightsManager;
import org.xwiki.contrib.changerequest.ChangeRequestStatus;
import org.xwiki.contrib.changerequest.storage.ChangeRequestStorageManager;
import org.xwiki.contrib.rights.RightUpdatedEvent;
import org.xwiki.contrib.rights.SecurityRuleDiff;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.observation.event.Event;
import org.xwiki.refactoring.internal.listener.AbstractDocumentEventListener;
import org.xwiki.security.authorization.ReadableSecurityRule;
import org.xwiki.security.authorization.Right;

/**
 * Component in charge of synchronizing rights between pages and change request.
 *
 * @version $Id$
 * @since 0.7
 */
@Component
@Singleton
public class RightsUpdatedListener extends AbstractDocumentEventListener
{
    static final String NAME = "org.xwiki.contrib.changerequest.internal.RightsUpdatedListener";

    @Inject
    private ChangeRequestStorageManager changeRequestStorageManager;

    @Inject
    private ChangeRequestConfiguration configuration;

    @Inject
    private ChangeRequestRightsManager changeRequestRightsManager;

    @Inject
    private Logger logger;

    /**
     * Default constructor.
     */
    public RightsUpdatedListener()
    {
        super(NAME, new RightUpdatedEvent());
    }

    @Override
    public void processLocalEvent(Event event, Object source, Object data)
    {
        EntityReference entityReference = (EntityReference) source;
        List<SecurityRuleDiff> securityRuleDiffList = (List<SecurityRuleDiff>) data;

        // we ignore changes applied at wiki level and that concerns the change request location itself
        if (entityReference.getType() != EntityType.WIKI
            && !entityReference.hasParent(configuration.getChangeRequestSpaceLocation())) {
            try {
                DocumentReference reference;
                if (entityReference.getType() == EntityType.SPACE) {
                    reference = new DocumentReference("WebHome", new SpaceReference(entityReference));
                } else {
                    reference = new DocumentReference(entityReference);
                }
                List<ChangeRequest> changeRequests =
                    changeRequestStorageManager.findChangeRequestTargeting(reference);

                Set<DocumentReference> ruleSubjects = this.computeRulesSubjects(securityRuleDiffList);

                if (!changeRequests.isEmpty() && !ruleSubjects.isEmpty()) {
                    for (ChangeRequest changeRequest : changeRequests) {
                        ChangeRequestStatus status = changeRequest.getStatus();
                        // if  the change request is merged, we don't want to edit its rights.
                        if (status == ChangeRequestStatus.MERGED) {
                            continue;
                        // if it's closed, we don't want to split it, we just edit the rights no matter the consequences
                        } else if (status == ChangeRequestStatus.CLOSED) {
                            this.changeRequestRightsManager.copyViewRights(changeRequest, reference);
                        } else {
                            this.handleOpenChangeRequest(changeRequest, ruleSubjects, reference);
                        }
                    }
                }
            } catch (ChangeRequestException e) {
                logger.warn("Error while trying to syncing rights after event [{}]: [{}]", event,
                    ExceptionUtils.getRootCauseMessage(e));
            }
        }
    }

    private void handleOpenChangeRequest(ChangeRequest changeRequest, Set<DocumentReference> ruleSubjects,
        DocumentReference reference) throws ChangeRequestException
    {
        if (this.changeRequestRightsManager.isViewAccessStillConsistent(changeRequest,
            ruleSubjects)) {
            this.changeRequestRightsManager.copyViewRights(changeRequest, reference);
        } else {
            List<ChangeRequest> splittedChangeRequests =
                this.changeRequestStorageManager.split(changeRequest);
            for (ChangeRequest splittedChangeRequest : splittedChangeRequests) {
                if (splittedChangeRequest.getModifiedDocuments().contains(reference)) {
                    this.changeRequestRightsManager.copyViewRights(splittedChangeRequest,
                        reference);
                }
            }
        }
    }

    private Set<DocumentReference> computeRulesSubjects(List<SecurityRuleDiff> securityRuleDiffList)
    {
        Set<DocumentReference> ruleSubjects = new HashSet<>();
        for (SecurityRuleDiff securityRuleDiff : securityRuleDiffList) {
            ReadableSecurityRule currentRule = securityRuleDiff.getCurrentRule();
            ReadableSecurityRule previousRule = securityRuleDiff.getPreviousRule();
            boolean concernsView = (currentRule != null && currentRule.getRights().contains(Right.VIEW))
                || (previousRule != null && previousRule.getRights().contains(Right.VIEW));
            if (currentRule != null && concernsView) {
                ruleSubjects.addAll(currentRule.getUsers());
                ruleSubjects.addAll(currentRule.getGroups());
            }
            if (previousRule != null && concernsView) {
                ruleSubjects.addAll(previousRule.getUsers());
                ruleSubjects.addAll(previousRule.getGroups());
            }
        }
        return ruleSubjects;
    }
}
