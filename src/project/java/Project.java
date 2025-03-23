
/*
 * Copyright (C) 2025 The AUTO483 Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          https://opensource.org/licenses/MIT
 */
import static bee.api.License.*;

import javax.lang.model.SourceVersion;

public class Project extends bee.api.Project {
    {
        product("com.github.teletha", "auto483", ref("version.txt"));
        license(MIT);
        require(SourceVersion.RELEASE_24);

        versionControlSystem("https://github.com/teletha/auto483");
    }
}