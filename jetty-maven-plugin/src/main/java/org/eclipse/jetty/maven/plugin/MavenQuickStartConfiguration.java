//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.maven.plugin;

import org.eclipse.jetty.quickstart.QuickStartConfiguration;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MavenQuickStartConfiguration
 */
public class MavenQuickStartConfiguration extends QuickStartConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(QuickStartConfiguration.class);
    
    @Override
    public Class<? extends Configuration> replaces()
    {
        return QuickStartConfiguration.class;
    }
    
    @Override
    public void deconfigure(WebAppContext context) throws Exception
    {
        //if we're not persisting the temp dir, get rid of any overlays
        if (!context.isPersistTempDirectory())
        {
            Resource originalBases = (Resource)context.getAttribute("org.eclipse.jetty.resources.originalBases");
            String originalBaseStr = originalBases.toString();

            //Iterate over all of the resource bases and ignore any that were original bases, just
            //deleting the overlays
            Resource res = context.getBaseResource();
            if (res instanceof ResourceCollection)
            {
                for (Resource r : ((ResourceCollection)res).getResources())
                {
                    if (originalBaseStr.contains(r.toString()))
                        continue;
                    IO.delete(r.getFile());
                }
            }
        }
        super.deconfigure(context);
    }
}
