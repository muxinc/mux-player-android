#! /bin/sh

branch_name="${BUILDKITE_BRANCH}"
if [ -z "$branch_name" ]; then
    branch_name=$(git branch)
fi
export branch_name
commit="$(git rev-parse --short HEAD)"
export commit

sed -E \
  -e "s/TEMPLATE_BRANCH/${BUILDKITE_BRANCH}/g" \
  -e "s/TEMPLATE_COMMIT/${BUILDKITE_BRANCH}/g" \
  .sauce/template-conf-player.yml > .sauce/config.yml

#sed -E \
#  -e "s/TEMPLATE_BRANCH/${BUILDKITE_BRANCH}/g" \
#  -e "s/TEMPLATE_COMMIT/${BUILDKITE_BRANCH}/g" \
#  .sauce/template.sauce-player.yml > .sauce/config.yml
