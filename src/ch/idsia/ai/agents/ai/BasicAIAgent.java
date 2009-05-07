package ch.idsia.ai.agents.ai;

import ch.idsia.mario.environments.IEnvironment;
import ch.idsia.ai.agents.IAgent;

/**
 * Created by IntelliJ IDEA.
 * User: Sergey Karakovskiy
 * Date: Apr 25, 2009
 * Time: 12:30:41 AM
 * Package: com.mojang.mario.Agents
 */
public class BasicAIAgent implements IAgent 
{
    // Bla bla bla
    protected boolean Action[] = new boolean[IEnvironment.NumberOfActions];
    protected boolean EmptyAction[] = new boolean[IEnvironment.NumberOfActions];
    protected String Name = "Instance of BasicAIAgent. Change this name";

    public void reset()
    {
        Action = EmptyAction;
    }

    public boolean[] getAction(IEnvironment observation)
    {
        return EmptyAction;
    }

    public AGENT_TYPE getType()
    {
        return IAgent.AGENT_TYPE.AI;
    }

    public String getName() {        return Name;    }

    public void setName(String Name) { this.Name = Name;    }
}
