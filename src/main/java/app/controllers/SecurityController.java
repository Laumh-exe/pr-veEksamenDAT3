package app.controllers;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.dtos.TokenDTO;
import app.dtos.UserDTO;
import app.entities.User;
import app.exceptions.EntityNotFoundException;
import app.persistance.UserDAO;
import app.utils.TokenUtil;
import io.javalin.http.Handler;
import io.javalin.http.HttpStatus;
import io.javalin.validation.ValidationException;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityManagerFactory;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

public class SecurityController implements ISecurityController {
    private UserDAO userDAO;
    private ObjectMapper objectMapper = new ObjectMapper();

    public SecurityController(EntityManagerFactory emf) {
        userDAO = UserDAO.getUserDAOInstance(emf);
    }

    @Override
    public Handler register() {
        return (ctx) -> {
            ObjectNode returnObject = objectMapper.createObjectNode();
            try {
                UserDTO userInput = ctx.bodyAsClass(UserDTO.class);
                User created = userDAO.createUser(userInput.getUsername(), userInput.getPassword());

                String token = TokenUtil.createToken(new UserDTO(created));
                ctx.status(HttpStatus.CREATED).json(new TokenDTO(token, userInput.getUsername()));
            } catch (EntityExistsException e) {
                ctx.status(HttpStatus.UNPROCESSABLE_CONTENT);
                ctx.json(returnObject.put("msg", "User already exists"));
            }
        };
    }

    @Override
    public Handler login() {
        return (ctx) -> {
            ObjectNode returnObject = objectMapper.createObjectNode(); // for sending json messages back to the client
            try {
                UserDTO user = ctx.bodyAsClass(UserDTO.class);

                User verifiedUserEntity = userDAO.verifyUser(user.getUsername(), user.getPassword());

                String token = TokenUtil.createToken(new UserDTO(verifiedUserEntity));

                ctx.status(200).json(new TokenDTO(token, user.getUsername()));

            } catch (EntityNotFoundException | ValidationException e) {
                ctx.status(401);
                System.out.println(e.getMessage());
                ctx.json(returnObject.put("msg", e.getMessage()));
            }
        };
    }

    @Override
    public boolean authorize(UserDTO user, Set<String> allowedRoles) {
        // Called from the ApplicationConfig.setSecurityRoles

        AtomicBoolean hasAccess = new AtomicBoolean(false); // Since we update this in a lambda expression, we need to
                                                            // use an AtomicBoolean
        if (user != null) {
            user.getRoles().stream().forEach(role -> {
                if (allowedRoles.contains(role.toUpperCase())) {
                    hasAccess.set(true);
                }
            });
        }
        return hasAccess.get();
    }

    @Override
    public Handler authenticate() {
        // To check the users roles against the allowed roles for the endpoint (managed
        // by javalins accessManager)
        // Checked in 'before filter' -> Check for Authorization header to find token.
        // Find user inside the token, forward the ctx object with userDTO on attribute
        // When ctx hits the endpoint it will have the user on the attribute to check
        // for roles (ApplicationConfig -> accessManager)
        ObjectNode returnObject = objectMapper.createObjectNode();
        return (ctx) -> {
            if (ctx.method().toString().equals("OPTIONS")) {
                ctx.status(200);
                return;
            }
            String header = ctx.header("Authorization");
            if (header == null) {
                ctx.status(HttpStatus.FORBIDDEN).json(returnObject.put("msg", "Authorization header missing"));
                return;
            }
            String token = header.split(" ")[1];
            if (token == null) {
                ctx.status(HttpStatus.FORBIDDEN).json(returnObject.put("msg", "Authorization header malformed"));
                return;
            }
            UserDTO verifiedTokenUser = TokenUtil.verifyToken(token);
            if (verifiedTokenUser == null) {
                ctx.status(HttpStatus.FORBIDDEN).json(returnObject.put("msg", "Invalid User or Token"));
            }
            System.out.println("USER IN AUTHENTICATE: " + verifiedTokenUser);
            ctx.attribute("user", verifiedTokenUser);
        };
    }

    public Handler addRoleToUser() {
        return (ctx) -> {
            RoleAssignment roleAssignment = ctx.bodyAsClass(RoleAssignment.class);
            ObjectNode returnObject = objectMapper.createObjectNode();
            try {
                User user = userDAO.addRoleToUser(roleAssignment.username, roleAssignment.role);
                ctx.status(HttpStatus.OK).json(new UserDTO(user));
            } catch (EntityNotFoundException e) {
                ctx.status(HttpStatus.NOT_FOUND);
                ctx.json(returnObject.put("msg", e.getMessage()));
            }
        };
    }

    @NoArgsConstructor
    @AllArgsConstructor
    @Getter
    @Setter
    public static class RoleAssignment {
        private String username;
        private String role;
    }
}
