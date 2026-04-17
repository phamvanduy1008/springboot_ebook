package com.example.epub.api.dto.auth;

import java.util.*;

public record AccountResponse(UUID id,String email,String displayName,String bio,String role,boolean emailVerified,boolean hasPassword,List<String> providers) { }
