# TIGER Review plugin

We're building a JOSM plugin to help mappers reviewing roadways in the United States. In particular, we want to help them make inferences about the correctness of tagging based off of attributes of neaby features and connecting roadways.

We will mostly be looking at roadways (tag: highway) with a review tag from the original import ("tiger:reviewed"="no")

## Roadway name (name=)

For roadways with tiger:reviewed=no, look for corrobrating evidence that the name= tag is accurate. 

 - Connecting roadways - the roadway is connected to another roadways that has the same name but does not have tiger:reviewed=no
 - Address data - roadway name information can be corroborated using the fact that nearby features have matching addr:street tagging. The MapWithAI plugin does the reverse, looking for addresses with matching or mismatching roadway names (https://github.com/JOSM/MapWithAI)

 ## Surface tagging (surface=)

Many roadways do not have surface= tagged but it's often extremely useful for route planning.

 Similar to names, conected roadways very very often have the same surface. For roadways with tiger:reviewed=no, look for connecting roadways with sarface= tags already applied. If it's bounded on both ends by identical surface tags, that increases the likelihood of the tag being appropriate for that way.

 ## User experience

 Start by building validator warnings for these things. Identify these cases and generate fixups that can be one click applied by the user.